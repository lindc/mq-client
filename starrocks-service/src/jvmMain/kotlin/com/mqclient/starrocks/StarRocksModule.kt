package com.mqclient.starrocks

import com.mqclient.core.ConnectionStore
import com.mqclient.core.ServiceException
import com.mqclient.core.spi.ServiceContext
import com.mqclient.olap.base.connection.HealthReport
import com.mqclient.olap.base.query.ResultBatch
import com.mqclient.starrocks.jdbc.JdbcConnector
import com.mqclient.starrocks.jdbc.MysqlJdbcConnector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
     * SQL 控制台的查询入口（S3 MVP）：在当前共享会话上执行 [sql]，按批流式返回。
     * 未初始化时返回抛错的 Flow（UI 捕获后显示「未连接」类错误），不静默失败。
     */
    fun executeQuery(sql: String): Flow<ResultBatch> {
        val current = manager ?: return flow {
            throw IllegalStateException("StarRocks module is not initialized")
        }
        return current.executeQuery(sql)
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
        return current.connect(config).map { }
    }
}
