package com.mqclient.olap.base.connection

import com.mqclient.core.ServiceException
import com.mqclient.core.event.EventBus
import com.mqclient.core.log.Log
import com.mqclient.core.log.ModuleLogger
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.olap.base.OlapCategory
import com.mqclient.olap.base.OlapCategory.EVENT_NAMESPACE
import com.mqclient.olap.base.OlapCategory.PERF_TAG_PREFIX
import com.mqclient.olap.base.error.OlapErrorCode
import com.mqclient.olap.base.perf.olapTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一条 OLAP 连接的**健康状态报告**。
 *
 * @param healthy 是否健康（句柄在、探测成功）。
 * @param detail 人类可读的探测结果描述（协议探测语句 / 端点 / 失败原因）。
 * @param latencyMs 探测耗时（毫秒）；未探测（无会话）时为 -1。
 */
data class HealthReport(
    val healthy: Boolean,
    val detail: String,
    val latencyMs: Long = -1L,
)

/**
 * OLAP 连接生命周期的模板基类：**单连接管理器**（一个实例管一条连接）。
 *
 * ## 与 mq-base `AbstractConnectionManager` 的分工差异
 *
 * MQ 系是多连接注册表（`contexts` 表 + `ConnectionStore` 状态上报 + 定时扫描任务）；
 * OLAP 首版按「一条配置一个管理器」建模（OLAP 引擎连接数少、显式连接）。多连接
 * 注册表、连续失败阈值、定时扫描任务等能力属于 `AbstractCatalogScanService` 与
 * 未来的 olap-service 层，不在本基类 —— 不硬套（见设计映射表）。
 *
 * ## 句柄隔离规范（与 MQ 系同一口径）
 *
 *  - **重量级**：[AnalyticsSession]（HTTP client / JDBC pool / 原生连接）连接级共享，
 *    由本类持有，只在 [connect] / [close] 时创建销毁，初始化用 [Mutex] 串行化；
 *  - **轻量**：Query / ResultSet / ResultBatch 每操作独立（查询执行器负责），绝不
 *    跨请求复用。
 *
 * ## 并发
 *
 * `connect` / `close` / `healthCheck` 全部经 [mutex] 串行（单连接一把锁）；
 * [withSession] **不取锁**（快照当前会话执行，避免长查询挡住状态操作）—— 半开
 * 会话靠 [isSessionBroken] 检测后自动 [close] 自愈，与 RabbitMQ 那轮的
 * `invalidateAmqpConnection` 同构。
 *
 * 状态变更通过 EventBus 广播（事件基类复用 app-core 的 [com.mqclient.core.event.AppEvent]）：
 * 事件名 = `[EVENT_NAMESPACE].connection.connected / closed / failed`。
 *
 * @param C 连接配置类型。
 * @param S 会话类型。
 */
abstract class AbstractAnalyticsConnectionManager<C : AnalyticsConnectionConfig, S : AnalyticsSession>(
    protected val context: ServiceContext,
    protected val moduleId: String,
) {

    /** 模块日志句柄（tag = `[OLAP]<moduleId>`，前缀取 [OlapCategory.LOG_TAG_PREFIX]）。 */
    protected val log: ModuleLogger = Log.withTag("${OlapCategory.LOG_TAG_PREFIX}$moduleId")

    /** 单连接串行锁：connect / close / healthCheck 互斥。 */
    private val mutex = Mutex()

    /** 当前共享会话（连接级共享的重量级句柄；断开 / 失败时置 null）。 */
    @Volatile
    private var session: S? = null

    /** 建立当前会话时的配置快照（重连时复用）。 */
    @Volatile
    private var sessionConfig: C? = null

    /* ------------------------------ 子类钩子 ------------------------------ */

    /**
     * 建立会话并**验证连通性**（连不上就抛，不许返回半成品）。
     * 约定：失败（含取消）时把刚建的句柄关掉再抛 —— 基类拿不到半成品句柄。
     */
    protected abstract suspend fun openSession(config: C): S

    /** 关闭 [openSession] 建出来的句柄；必须自行兜异常（失败只记日志），幂等。 */
    protected abstract suspend fun closeSession(session: S)

    /** 健康探测本体（如 `SELECT 1` / `SHOW DATABASES` 的轻量探针）。 */
    protected abstract suspend fun checkHealth(session: S): HealthReport

    /**
     * 会话级故障识别：[withSession] 中命中时自动 [close]（半开连接自愈）。
     * 协议模块覆盖它做结构化判断（amqp-client 那轮的教训：文本匹配会误伤）。
     * 默认 false —— 会话仍按可用处理。
     */
    protected open fun isSessionBroken(error: Throwable): Boolean = false

    /** 会话级故障 → [OlapErrorCode]（审计与错误文案用）。默认 [OlapErrorCode.UNKNOWN]。 */
    protected open fun classifyError(error: Throwable): OlapErrorCode = OlapErrorCode.UNKNOWN

    /** 日志里的会话标识（各协议不同：JDBC url / HTTP 端点）。 */
    protected open fun describe(session: S): String = session.toString()

    /* ------------------------------ 公开 API ------------------------------ */

    /**
     * 建立连接：已有可用会话时幂等返回（重复点击无副作用）。
     *
     * 打 `[OLAP-Perf][connect]` 分段；成功 / 失败 / 关闭分别发
     * `olap.connection.connected / failed / closed` 事件（payload 带 connectionId）。
     */
    suspend fun connect(config: C): Result<S> = mutex.withLock {
        session?.takeIf { it.isUsable() }?.let {
            log.debug("connect ignored (already connected): connection=${config.connectionId}")
            return@withLock Result.success(it)
        }
        val start = olapTimeMillis()
        log.debug("connect start: connection=${config.connectionId}")
        try {
            val opened = openSession(config)
            session = opened
            sessionConfig = config
            val duration = olapTimeMillis() - start
            log.info(
                "$PERF_TAG_PREFIX[connect] connection=${config.connectionId} " +
                    "耗时=${duration}ms session=${describe(opened)}",
            )
            context.emit(
                name = "$EVENT_NAMESPACE.connection.connected",
                payload = mapOf("connectionId" to config.connectionId, "durationMs" to duration),
            )
            Result.success(opened)
        } catch (error: Throwable) {
            // 取消必须原样上抛，否则取消语义被吞（本项目反复踩过的坑）。
            if (error is CancellationException) throw error
            context.emit(
                name = "$EVENT_NAMESPACE.connection.failed",
                payload = mapOf(
                    "connectionId" to config.connectionId,
                    "message" to (error.message ?: error::class.simpleName ?: "unknown"),
                ),
            )
            log.warn("connect failed: connection=${config.connectionId} error=${error.message}")
            Result.failure(error)
        }
    }

    /**
     * 关闭连接：关会话、清引用、发 `olap.connection.closed`。
     * 无会话时是空操作（重复点「断开」无副作用）。
     */
    suspend fun close() = mutex.withLock {
        val current = session ?: return@withLock
        val connectionId = sessionConfig?.connectionId ?: "unknown"
        runCatching { closeSession(current) }
            .onFailure { log.warn("session close failed (ignored): error=${it.message}") }
        session = null
        sessionConfig = null
        log.info("disconnected: connection=$connectionId")
        context.emit(
            name = "$EVENT_NAMESPACE.connection.closed",
            payload = mapOf("connectionId" to connectionId),
        )
        Unit
    }

    /**
     * 健康探测：无会话时直接报告不健康（不发探针）；有会话时交给 [checkHealth]。
     * 探测失败**不断开连接** —— 半开判定交给 [isSessionBroken]（下一次业务操作触发自愈），
     * 探针失败可能只是瞬时抖动。
     */
    suspend fun healthCheck(): HealthReport {
        val current = session ?: return HealthReport(
            healthy = false,
            detail = "No active session",
        )
        val start = olapTimeMillis()
        return try {
            val report = checkHealth(current)
            report.copy(latencyMs = olapTimeMillis() - start)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            HealthReport(
                healthy = false,
                detail = error.message ?: error::class.simpleName ?: "unknown",
                latencyMs = olapTimeMillis() - start,
            )
        }
    }

    /** 会话句柄是否仍然存在（供扫描健康度区分「真没连接」与「单次请求失败」）。 */
    fun isAlive(): Boolean = session?.isUsable() == true

    /**
     * 在**当前共享会话**上执行一段工作。
     *
     * 不取 [mutex]：长查询不能挡住 connect / close / healthCheck（等锁等于把状态
     * 操作一起拖死）。无会话时抛 [OlapErrorCode.NOT_CONNECTED]；块内异常命中
     * [isSessionBroken] 时自动 [close] 自愈（下一次 withSession 会得到明确的
     * 「未连接」错误而不是复用半开会话），原异常照常上抛。
     */
    suspend fun <T> withSession(block: suspend (S) -> T): T {
        val current = session ?: throw ServiceException(
            errorKey = OlapErrorCode.NOT_CONNECTED.errorKey,
            fallbackMessage = OlapErrorCode.NOT_CONNECTED.fallbackMessage,
        )
        try {
            return block(current)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (isSessionBroken(error)) {
                log.warn(
                    "session broken detected, closing for self-heal: " +
                        "error=${error::class.simpleName} detail=${error.message}",
                )
                close()
            }
            throw error
        }
    }

    private companion object
}
