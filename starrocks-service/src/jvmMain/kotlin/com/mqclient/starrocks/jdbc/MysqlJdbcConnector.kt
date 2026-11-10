package com.mqclient.starrocks.jdbc

import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.Statement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 真实 JDBC 连接器：[JdbcConnector] 的 MySQL 协议实现（StarRocks FE 走 MySQL 协议）。
 *
 * 所有阻塞 JDBC 调用都经 [withContext] 切到 [Dispatchers.IO] —— 不阻塞调用方
 * （UI / 协程主线程）。`SQLException` 在这里就地翻译成平台中立的
 * [JdbcFailure]（携带 SQLState / ErrorCode）或 [JdbcConnectTimeoutException]，
 * 使 commonMain 的模板层与 commonTest 都不需要认识 `java.sql`。
 *
 * 驱动由 ServiceLoader 自动注册；`Class.forName` 只是兜底（某些打包形态下
 * 显式加载更稳），失败不阻断（缺驱动会在 getConnection 时以 JdbcFailure 暴露）。
 */
class MysqlJdbcConnector : JdbcConnector {

    init {
        runCatching { Class.forName("com.mysql.cj.jdbc.Driver") }
    }

    /** 建连接：阻塞调用切 IO；SQLException → JdbcFailure / Timeout。 */
    override suspend fun connect(url: String, user: String, password: String): JdbcConnection =
        withContext(Dispatchers.IO) {
            try {
                val raw: Connection = java.sql.DriverManager.getConnection(url, user, password)
                MysqlJdbcConnection(raw)
            } catch (error: SQLTimeoutException) {
                throw JdbcConnectTimeoutException(error.message ?: "connect timed out")
            } catch (error: SQLException) {
                throw JdbcFailure(error.message, error.sqlState, error.errorCode, error)
            }
        }

    /** DriverManager 无显式释放入口；保留钩子供将来持有连接池时实现。 */
    override suspend fun close() = Unit

    /** 单条物理连接的平台中立适配（轻量句柄，即用即毁）。 */
    private class MysqlJdbcConnection(private val raw: Connection) : JdbcConnection {

        /** `SELECT 1`：Statement / ResultSet 用 `use` 保证关闭。 */
        override suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
            try {
                raw.createStatement().use { statement: Statement ->
                    statement.executeQuery(PROBE_SQL).use { rs -> rs.next() }
                }
            } catch (error: SQLTimeoutException) {
                throw JdbcConnectTimeoutException(error.message ?: "query timed out")
            } catch (error: SQLException) {
                throw JdbcFailure(error.message, error.sqlState, error.errorCode, error)
            }
        }

        /** `isClosed` 本身会抛 SQLException，兜成 false（按不可用处理）。 */
        override fun isUsable(): Boolean = runCatching { !raw.isClosed }.getOrDefault(false)

        /** 幂等关闭，异常自行吞掉（断开路径不能被关不掉的句柄中断）。 */
        override suspend fun close() = withContext(Dispatchers.IO) {
            runCatching { raw.close() }
            Unit
        }

        private companion object {
            /** 探针语句（StarRocks FE 支持无表 SELECT 常量）。 */
            const val PROBE_SQL: String = "SELECT 1"
        }
    }
}
