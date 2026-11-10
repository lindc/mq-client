package com.mqclient.olap.base.catalog

import com.mqclient.core.ServiceException
import com.mqclient.core.log.Log
import com.mqclient.core.log.ModuleLogger
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.olap.base.OlapCategory
import com.mqclient.olap.base.OlapCategory.EVENT_NAMESPACE
import com.mqclient.olap.base.connection.AnalyticsConnectionConfig
import com.mqclient.olap.base.connection.AnalyticsSession
import com.mqclient.olap.base.error.OlapErrorCode
import com.mqclient.olap.base.perf.olapTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * 目录扫描服务的模板基类：分阶段（库 → 表 → 列）枚举一个 OLAP 引擎的元数据目录。
 *
 * ## 与 mq-base `AbstractScanService` 的同构与差异
 *
 * 同构的部分（逐条对齐）：
 *  - **分阶段 + 可取消**：每个阶段、每个对象前 `ensureActive`（协程取消立即退出，
 *    与 MQ 系「取消不是失败」同一处理：[CancellationException] 原样上抛）；
 *  - **单对象失败不中断整体**：[scanStageOr] 对齐 mq-base 的 `scanKindOr` ——
 *    失败记入 [CatalogSnapshot.failures] 并降级为空，取消原样上抛；
 *  - **错误统一翻译**：[errorMessage] 钩子默认取 message / 类名，协议模块覆盖。
 *
 * 差异（不硬套的部分）：MQ 系有「慢扫 / 快扫双循环 + 连续失败阈值 + 定时任务注册」，
 * 目录扫描是**显式触发的单轮操作**（OLAP 元数据变化不频繁，不做定时心跳）——
 * 手动扫描 / 阈值 / TTL 全部留给未来的 olap-service 层（需要时按 mq-base 口径补）。
 *
 * 会话管理：本类自管会话生命周期（[scanCatalog] 内 open → scan → close），
 * 不依赖连接管理器 —— 目录扫描是低频重操作，独立的短会话符合「句柄即用即毁」。
 *
 * 状态变更通过 EventBus 广播：`olap.catalog.scan.completed / scan.failed`。
 *
 * @param C 连接配置类型。
 * @param S 会话类型。
 */
abstract class AbstractCatalogScanService<C : AnalyticsConnectionConfig, S : AnalyticsSession>(
    protected val context: ServiceContext,
    protected val moduleId: String,
) {

    /** 模块日志句柄（tag = `[OLAP]<moduleId>`）。 */
    protected val log: ModuleLogger = Log.withTag("${OlapCategory.LOG_TAG_PREFIX}$moduleId")

    /* ------------------------------ 子类钩子 ------------------------------ */

    /** 建立会话（失败自行关掉半成品再抛，与连接管理器同一约定）。 */
    protected abstract suspend fun openSession(config: C): S

    /** 关闭会话（自行兜异常，幂等）。 */
    protected abstract suspend fun closeSession(session: S)

    /** 枚举数据库（按 [CatalogScope] 过滤系统库 / 指定库）。 */
    protected abstract suspend fun listDatabases(session: S, scope: CatalogScope): List<CatalogNode.Database>

    /** 枚举一个库下的表 / 视图（超过 [CatalogScope.maxTablesPerDatabase] 时截断并置 truncated）。 */
    protected abstract suspend fun listTables(
        session: S,
        scope: CatalogScope,
        database: String,
    ): List<CatalogNode>

    /** 枚举一张表的列 / 分区 / 副本。返回空列表表示该表无此层级信息（合法）。 */
    protected abstract suspend fun listColumns(
        session: S,
        scope: CatalogScope,
        database: String,
        table: String,
    ): List<CatalogNode>

    /**
     * 异常 → 失败文案（进 [CatalogSnapshot.failures] 与日志）。
     * 默认取 message / 类名；协议模块覆盖它做结构化翻译（对齐 mq-base `errorMessage`）。
     */
    protected open fun errorMessage(error: Throwable): String =
        error.message ?: error::class.simpleName ?: "unknown"

    /* ------------------------------ 扫描入口 ------------------------------ */

    /**
     * 执行一轮目录扫描。
     *
     * 每个阶段打 `[OLAP-Perf][catalog]` 分段；每阶段、每对象前 `ensureActive`；
     * 单对象失败经 [scanStageOr] 记入 failures 后继续。整轮结束发
     * `olap.catalog.scan.completed`（或全部阶段失败时 `olap.catalog.scan.failed`）。
     *
     * 会话在 finally 中关闭（短会话，即用即毁）。
     *
     * @param config 连接配置。
     * @param scope 扫描范围。
     * @param onProgress 进度回调（阶段切换与每对象完成时触发；可为空操作）。
     */
    suspend fun scanCatalog(
        config: C,
        scope: CatalogScope,
        onProgress: (ScanProgress) -> Unit = {},
    ): CatalogSnapshot {
        val start = olapTimeMillis()
        var truncated = false
        val nodes = mutableListOf<CatalogNode>()
        val failures = mutableMapOf<String, String>()
        val session = try {
            openSession(config)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val message = errorMessage(error)
            failures["<open>"] = message
            log.error("catalog scan failed (session open): connection=${config.connectionId} error=$message")
            context.emit(
                name = "$EVENT_NAMESPACE.catalog.scan.failed",
                payload = mapOf("connectionId" to config.connectionId, "message" to message),
            )
            return CatalogSnapshot(nodes, failures, olapTimeMillis() - start)
        }
        try {
            currentCoroutineContext().ensureActive()

            // ---- 阶段 1：枚举数据库 ----
            val databases = scanStageOr(failures, "<databases>", emptyList()) {
                listDatabases(session, scope)
                    .also { onProgress(ScanProgress(CatalogPhase.DATABASES, "", it.size, it.size)) }
            }
            nodes += databases

            // ---- 阶段 2：逐库枚举表 ----
            val tablesByDatabase = mutableMapOf<CatalogNode.Database, List<CatalogNode>>()
            databases.forEachIndexed { index, database ->
                currentCoroutineContext().ensureActive()
                val tables = scanStageOr(failures, database.name, emptyList()) {
                    val result = listTables(session, scope, database.name)
                    if (result.size >= scope.maxTablesPerDatabase) truncated = true
                    result
                }
                tablesByDatabase[database] = tables
                nodes += tables
                onProgress(ScanProgress(CatalogPhase.TABLES, database.name, index + 1, databases.size))
            }

            // ---- 阶段 3：逐表枚举列 / 分区 / 副本 ----
            val tables = tablesByDatabase.values.flatten()
                .filterIsInstance<CatalogNode.Table>()
            tables.forEachIndexed { index, table ->
                currentCoroutineContext().ensureActive()
                val qualified = "${table.database}.${table.name}"
                val details = scanStageOr(failures, qualified, emptyList()) {
                    listColumns(session, scope, table.database, table.name)
                }
                nodes += details
                onProgress(ScanProgress(CatalogPhase.COLUMNS, qualified, index + 1, tables.size))
            }

            val duration = olapTimeMillis() - start
            log.info(
                "${OlapCategory.PERF_TAG_PREFIX}[catalog] connection=${config.connectionId} " +
                    "耗时=${duration}ms nodes=${nodes.size} failures=${failures.size} truncated=$truncated",
            )
            context.emit(
                name = "$EVENT_NAMESPACE.catalog.scan.completed",
                payload = mapOf(
                    "connectionId" to config.connectionId,
                    "nodeCount" to nodes.size,
                    "failureCount" to failures.size,
                    "durationMs" to duration,
                ),
            )
            return CatalogSnapshot(nodes, failures, duration, truncated)
        } finally {
            runCatching { closeSession(session) }
                .onFailure { log.warn("catalog scan session close failed (ignored): error=${it.message}") }
        }
    }

    /* ------------------------------ 内部 ------------------------------ */

    /**
     * 单阶段兜底：失败 → [fallback] 且记入 [failures]（带错误文案），取消原样上抛。
     * 对齐 mq-base `scanKindOr` 的语义与注释理由：`runCatching` 会把取消当成
     * 「这一段扫空了」，剩下的请求还接着发 —— 本项目反复踩过的坑。
     */
    private suspend fun <T> scanStageOr(
        failures: MutableMap<String, String>,
        stage: String,
        fallback: T,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        val message = errorMessage(error)
        failures[stage] = message
        log.warn("catalog scan stage skipped: stage=$stage error=$message")
        fallback
    }
}
