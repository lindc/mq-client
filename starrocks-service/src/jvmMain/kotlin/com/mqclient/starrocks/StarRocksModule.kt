package com.mqclient.starrocks

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ServiceException
import com.mqclient.core.event.EventNames
import com.mqclient.olap.base.perf.olapTimeMillis
import com.mqclient.core.newUuidV7
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.olap.base.connection.HealthReport
import com.mqclient.olap.base.query.ResultBatch
import com.mqclient.starrocks.jdbc.JdbcConnector
import com.mqclient.starrocks.jdbc.MysqlJdbcConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext

/**
 * StarRocks service 层的**模块门面**：把 S1 的 [StarRocksConnectionManager] 暴露给
 * UI 表单，而不让 UI 认识 `ServiceContext` / `java.sql`。
 *
 * 生命周期由 [StarRocksServiceAdapter.initialize] / `dispose` 驱动（宿主
 * `ModuleRegistry.initializeServices` 在启动时注入 [ServiceContext]）。
 * 门面持有注入了真实 [JdbcConnector] 的管理器，按次提供 [testConnection] ——
 * 表单的「测试连接」按钮直接调它，复用 S1 的 `SELECT 1` +
 * `[OLAP-Perf][connect]` 打点 + finally 关闭逻辑，不重复实现。
 *
 * 未初始化时返回 `healthy=false` 的报告而不是抛异常：表单把它当普通失败展示，
 * 不会让新建连接对话框崩掉。
 */
object StarRocksModule {

    @Volatile
    private var manager: StarRocksConnectionManager? = null

    /** SPI 注入的运行时上下文：连接注册表（连接查找 / 状态上报）与审计事件都经它。 */
    @Volatile
    private var context: ServiceContext? = null

    /** 真实 JDBC 连接器（jvmMain）；UI 侧只通过 [testConnection] 间接使用。 */
    private val connector: JdbcConnector = MysqlJdbcConnector()

    /** SPI 初始化钩子：由 ServiceAdapter 在宿主启动时调用。 */
    fun initialize(context: ServiceContext) {
        this.context = context
        manager = StarRocksConnectionManager(context, connector = connector)
    }

    /**
     * 退出钩子：**先同步断开共享会话**再摘引用（对齐 KafkaModule.shutdown 的
     * 口径 —— ModuleRegistry.disposeServices 不挂起，退出路径不能留下没关的
     * 物理连接；`runBlocking` 等会话关完才返回）。关闭失败只记日志，不阻断
     * 其余模块的销毁。
     */
    fun dispose() {
        val managerToStop = manager
        manager = null
        managerToStop?.let { current ->
            runCatching { kotlinx.coroutines.runBlocking { current.shutdown() } }
                .onFailure {
                    com.mqclient.core.log.Log.warn(
                        "olap-starrocks",
                        "module dispose: session shutdown failed (ignored): ${it.message}",
                    )
                }
        }
    }

    /**
     * 一次性连接测试（UI「测试连接」按钮 → S1 的真实探针）。
     * 不复用共享会话：探针自带 connect → SELECT 1 → finally close。
     */
    suspend fun testConnection(config: StarRocksConnectionConfig): HealthReport =
        manager?.testConnection(config) ?: HealthReport(
            healthy = false,
            detail = "StarRocks module is not initialized",
        )

    /**
     * SQL 控制台的查询入口（S3）：在当前共享会话上执行 [sql]，按批流式返回。
     *
     * ## 执行历史落库（S4）
     *
     * [onCompletion] 是唯一的执行咽喉点：流正常收完（SUCCESS）或异常结束
     * （FAILED，含 SQLException / 取消）都会回调，在此计算耗时并组装**自包含
     * payload** 广播 [EventNames.OLAP_QUERY_EXECUTED]。**只记 SQL 原文 + 状态 +
     * 耗时 + 错误信息，绝不记结果集**（见 olap_query.sq 表头）—— 10 万行的结果
     * 落盘会把本地库撑爆。
     *
     * 与 IBM MQ 的 mq_command 审计完全同款「事件自包含 + 咽喉点组装」：落库统一
     * 在核内 handler（OlapQueryHistoryHandler），模块不再直取 repository /
     * `CoreModule.scope` 单例 —— emit 不挂起、不阻塞 Flow 收集，写盘失败走核内
     * 错误通道，绝不反噬查询结果。
     */
    fun executeQuery(connectionId: String, sql: String): Flow<ResultBatch> {
        val current = manager ?: return flow {
            throw IllegalStateException("StarRocks module is not initialized")
        }
        val startedAt = olapTimeMillis()
        val recordId = newUuidV7()
        return flow {
            // 身份守卫 + 懒建连下沉：会话不属于该连接时自动抢占 / 补连（单槽语义，
            // 抢占会断开旧连接的会话；UI 侧的 isAlive 预检只是加速路径，不再是
            // 正确性依赖）。连接查不到 / 建连失败原样抛给收集方。
            if (!current.isAlive(connectionId)) {
                current.connect(findConfig(connectionId)).getOrThrow()
            }
            // 单槽串行由 manager.executeQuery 内的 queryMutex 保证（此处只做
            // 身份守卫 + 懒建连，守卫不取锁、connect 自带 manager 级互斥）。
            current.executeQuery(sql).collect { batch -> emit(batch) }
        }.onCompletion { cause ->
            val recordContext = context ?: return@onCompletion  // 未初始化（无界面脚本）时静默跳过
            recordContext.emit(
                name = EventNames.OLAP_QUERY_EXECUTED,
                payload = mapOf(
                    "recordId" to recordId,
                    "connectionId" to connectionId,
                    "sqlText" to sql,
                    "status" to if (cause == null) "SUCCESS" else "FAILED",
                    "durationMs" to (olapTimeMillis() - startedAt),
                    "errorMessage" to cause?.let {
                        if (it is CancellationException) "cancelled" else (it.message ?: it::class.simpleName)
                    },
                    "createdAt" to startedAt,
                ),
            )
        }
    }

    /* ------------------------------ 懒加载连接（S3） ------------------------------ */

    /**
     * 共享会话是否存活**且属于 [connectionId]**（SQL 控制台「执行」前的预检）。
     * 会话属于别的连接时返回 false —— 调用方 connectById / executeQuery 会自动
     * 抢占（单槽语义，见 [AbstractAnalyticsConnectionManager.connect]）。
     */
    fun isAlive(connectionId: String): Boolean = manager?.isAlive(connectionId) == true

    /** 按连接 id 从注入的注册表还原配置（找不到连接抛 [ServiceException]）。 */
    private fun findConfig(connectionId: String): StarRocksConnectionConfig {
        val info: ConnectionInfo = context?.connectionRegistry?.findConnection(connectionId)
            ?: throw ServiceException(
                errorKey = "err_conn_not_found",
                args = listOf(connectionId),
                fallbackMessage = "Connection '$connectionId' not found",
            )
        return StarRocksConnectionConfig.fromOptions(info.id, info.name, info.options)
    }

    /**
     * 按**落盘连接**建立会话（SQL 控制台「点击执行自动连接」的入口）。
     *
     * 从注入的 [ServiceContext.connectionRegistry] 按 [connectionId] 找 [ConnectionInfo]，options
     * 还原成 [StarRocksConnectionConfig] 后交 [StarRocksConnectionManager.connect]
     * —— 基类对「已有可用会话」幂等返回，**自动建好的 Session 保存在 manager 里**，
     * 后续执行直接复用（单槽语义，与右侧树上手动连接同一份会话）。
     *
     * 失败以 `Result.failure` 返回（找不到连接 → [ServiceException]；建连失败 →
     * 底层 `JdbcFailure` 等），UI 用 `userMessage()` 翻译后展示。
     *
     * ## 连接状态同步（左侧树变绿的关键）
     *
     * 项目约定：连接状态**不走 EventBus**（状态用 StateFlow，见 EventNames），左侧树
     * 订阅的是注册表的 `connections` 这份 StateFlow（`collectAsState`）。而
     * `AbstractAnalyticsConnectionManager.connect` 只发了
     * `olap.connection.connected` 事件、**没有** reportStatus —— 正是「执行触发了
     * 底层连接，树却仍显示未连接」的断点。这里按 MQ 模块同款（mq-base
     * `setStatus` / IbmMQService 心跳）在 connect 成功 / 失败时上报事实状态，
     * `connectionId` 直接取树节点传下来的 id，与快照里的 `ConnectionInfo.id`
     * 严格一致。
     */
    suspend fun connectById(connectionId: String): Result<Unit> {
        val current = manager ?: return Result.failure(
            IllegalStateException("StarRocks module is not initialized"),
        )
        val config = try {
            findConfig(connectionId)
        } catch (error: ServiceException) {
            return Result.failure(error)
        }
        // 抢占前记下旧会话归属：connect 成功且换了连接时，旧连接的树状态要退下。
        val previousId = current.currentConnectionId()
        val result = current.connect(config).map { }
        if (result.isSuccess) {
            if (previousId != null && previousId != connectionId) {
                reportStatus(previousId, ConnectionStatus.Disconnected)
            }
            reportStatus(connectionId, ConnectionStatus.Connected)
        } else {
            reportStatus(connectionId, ConnectionStatus.Failed)
        }
        return result
    }

    /**
     * 上报连接状态：优先切 [Dispatchers.Main]（UI 事实在主线程刷新），无 Main
     * dispatcher 的无界面宿主回退当前线程 —— `reportStatus` 本身是
     * `@Synchronized` 的 StateFlow 更新，线程安全，`collectAsState` 在组合线程
     * 消费，不会因线程归属丢事件。
     */
    private suspend fun reportStatus(connectionId: String, status: ConnectionStatus) {
        val registry = context?.connectionRegistry ?: return
        val onMain = runCatching {
            withContext(Dispatchers.Main) {
                registry.reportStatus(connectionId, status, StarRocksConstants.PROTOCOL_ID)
            }
        }
        if (onMain.isFailure) {
            registry.reportStatus(connectionId, status, StarRocksConstants.PROTOCOL_ID)
        }
    }
}
