package com.mqclient.starrocks

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.CoreModule
import com.mqclient.core.ServiceException
import com.mqclient.core.db.OlapQueryRecord
import com.mqclient.core.db.OlapQueryRepository
import com.mqclient.olap.base.perf.olapTimeMillis
import com.mqclient.core.newUuidV7
import com.mqclient.core.spi.ServiceContext
import com.mqclient.olap.base.connection.HealthReport
import com.mqclient.olap.base.query.ResultBatch
import com.mqclient.starrocks.jdbc.JdbcConnector
import com.mqclient.starrocks.jdbc.MysqlJdbcConnector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
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

    /** 真实 JDBC 连接器（jvmMain）；UI 侧只通过 [testConnection] 间接使用。 */
    private val connector: JdbcConnector = MysqlJdbcConnector()

    /** SPI 初始化钩子：由 ServiceAdapter 在宿主启动时调用。 */
    fun initialize(context: ServiceContext) {
        manager = StarRocksConnectionManager(context, connector = connector)
    }

    /** 退出钩子：摘掉引用（S2 接 catalog / query 后再扩资源释放）。 */
    fun dispose() {
        manager = null
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
     * （FAILED，含 SQLException / 取消）都会回调，在此计算耗时并组装
     * [OlapQueryRecord]。**只记 SQL 原文 + 状态 + 耗时 + 错误信息，绝不记结果集**
     * （见 olap_query.sq 表头）—— 10 万行的结果落盘会把本地库撑爆。
     *
     * 落库是 fire-and-forget：挂到应用级 [CoreModule.scope] 上切
     * [Dispatchers.IO]，不挂起 Flow 收集 —— 磁盘 IO 不阻塞 UI 拿到结果的速度；
     * 写入失败只记日志、绝不反噬查询结果。与 IBM MQ 的 mq_command 审计表同款
     * 「事件自包含 + 咽喉点组装」，区别是这里直接调 repository（少一层事件）。
     */
    fun executeQuery(connectionId: String, sql: String): Flow<ResultBatch> {
        val current = manager ?: return flow {
            throw IllegalStateException("StarRocks module is not initialized")
        }
        val startedAt = olapTimeMillis()
        val recordId = newUuidV7()
        return current.executeQuery(sql).onCompletion { cause ->
            val cancelled = cause is CancellationException
            val record = OlapQueryRecord(
                id = recordId,
                connectionId = connectionId,
                sqlText = sql,
                status = if (cause == null) OlapQueryRecord.STATUS_SUCCESS
                else OlapQueryRecord.STATUS_FAILED,
                durationMs = olapTimeMillis() - startedAt,
                errorMessage = cause?.let {
                    if (cancelled) "cancelled" else (it.message ?: it::class.simpleName)
                },
                createdAt = startedAt,
            )
            // 异步落库：不阻塞收集协程；CoreModule 未初始化（无界面脚本）时静默跳过。
            runCatching {
                CoreModule.scope.launch(Dispatchers.IO) {
                    runCatching { OlapQueryRepository.save(record) }
                        .onFailure { error ->
                            com.mqclient.core.log.Log.warn(
                                "olap-starrocks",
                                "query history save failed: ${error.message}",
                            )
                        }
                }
            }
        }
    }

    /* ------------------------------ 懒加载连接（S3） ------------------------------ */

    /** 共享会话是否存活（SQL 控制台「执行」前判断是否需要自动连接）。 */
    fun isAlive(): Boolean = manager?.isAlive() == true

    /**
     * 按**落盘连接**建立会话（SQL 控制台「点击执行自动连接」的入口）。
     *
     * 从 [ConnectionStore] 的连接快照按 [connectionId] 找 [ConnectionInfo]，options
     * 还原成 [StarRocksConnectionConfig] 后交 [StarRocksConnectionManager.connect]
     * —— 基类对「已有可用会话」幂等返回，**自动建好的 Session 保存在 manager 里**，
     * 后续执行直接复用（单槽语义，与右侧树上手动连接同一份会话）。
     *
     * 失败以 `Result.failure` 返回（找不到连接 → [ServiceException]；建连失败 →
     * 底层 `JdbcFailure` 等），UI 用 `userMessage()` 翻译后展示。
     *
     * ## 连接状态同步（左侧树变绿的关键）
     *
     * 项目约定：连接状态**不走 EventBus**（`EventNames` 注释明确「状态不走事件，
     * 模块直接调 `ConnectionStore.reportStatus()`」），左侧树订阅的是
     * `ConnectionStore.connections` 这份 StateFlow（`collectAsState`）。而
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
        val info = ConnectionStore.connections.value.firstOrNull { it.id == connectionId }
            ?: return Result.failure(
                ServiceException(
                    errorKey = "err_conn_not_found",
                    args = listOf(connectionId),
                    fallbackMessage = "Connection '$connectionId' not found",
                ),
            )
        val config = StarRocksConnectionConfig.fromOptions(info.id, info.name, info.options)
        val result = current.connect(config).map { }
        reportStatus(
            connectionId,
            if (result.isSuccess) ConnectionStatus.Connected else ConnectionStatus.Failed,
        )
        return result
    }

    /**
     * 上报连接状态：优先切 [Dispatchers.Main]（UI 事实在主线程刷新），无 Main
     * dispatcher 的无界面宿主回退当前线程 —— `reportStatus` 本身是
     * `@Synchronized` 的 StateFlow 更新，线程安全，`collectAsState` 在组合线程
     * 消费，不会因线程归属丢事件。
     */
    private suspend fun reportStatus(connectionId: String, status: ConnectionStatus) {
        val onMain = runCatching {
            withContext(Dispatchers.Main) {
                ConnectionStore.reportStatus(connectionId, status, StarRocksConstants.PROTOCOL_ID)
            }
        }
        if (onMain.isFailure) {
            ConnectionStore.reportStatus(connectionId, status, StarRocksConstants.PROTOCOL_ID)
        }
    }
}
