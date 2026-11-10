package com.mqclient.starrocks

import com.mqclient.core.spi.ServiceContext
import com.mqclient.olap.base.connection.HealthReport
import com.mqclient.starrocks.jdbc.JdbcConnector
import com.mqclient.starrocks.jdbc.MysqlJdbcConnector

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
}
