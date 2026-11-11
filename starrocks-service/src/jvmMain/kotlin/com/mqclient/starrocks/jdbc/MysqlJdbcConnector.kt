package com.mqclient.starrocks.jdbc

import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.Statement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

        /**
         * 流式执行：阻塞的 ResultSet 迭代在 `Dispatchers.IO` 协程里推进，批次经
         * [Channel] 交还调用方协程回调 —— [onBatch] 不在 IO 上下文执行，满足
         * `flow {}` 的 emit 上下文不变量（契约见 [JdbcConnection.executeQuery]）。
         *
         * 非查询语句（`execute` 返回 false）回调单个 `updateCount` 批次；
         * 空结果集回调单个空 schema 的末批。
         *
         * ## 中断清理（「报错后无法再次执行」的根因修复）
         *
         * 行级流式（`fetchSize = MIN_VALUE`）下，同一连接同时只能有一个活跃的
         * 流式 ResultSet：消费端中断（取消 / 截断 / 异常）时若不清理，残留的
         * 未读完 ResultSet 会让**这条连接报废** —— 后续任何查询都抛
         * "Streaming result set is still active"。同时阻塞在 `rs.next()` 的
         * IO 协程不响应协程取消，`coroutineScope` 会悬等它跑完（可能几十万行）。
         *
         * 因此 Statement 由**消费协程**创建并持有引用：`finally` 里一旦发现未
         * 自然完成，强制 `producer.cancel()` + 关 Statement + **关整条连接**
         * （`isUsable` 随即变 false，manager 的懒加载路径 `isAlive()` 会自动
         * 重连重建会话）—— 卡死的两端同时解开。
         */
        override suspend fun executeQuery(
            sql: String,
            batchSize: Int,
            onBatch: suspend (JdbcQueryBatch) -> Unit,
        ) {
            // Statement 在消费协程创建并持引用（生产协程只负责读），中断时消费端
            // 才有能力从资源侧强制解开阻塞。
            //
            // coroutineScope 的「悬等生产协程」问题（消费端取消后等阻塞 IO 跑完）
            // 由下方 finally 的强制断连解决：socket 一关，阻塞的 rs.next() 立即
            // 抛错返回，scope 无需等待几十万行读完。
            val statement: Statement = withContext(Dispatchers.IO) { raw.createStatement() }
            var completed = false
            try {
                coroutineScope {
                    val batches = Channel<JdbcQueryBatch>(Channel.UNLIMITED)
                    val producer = launch(Dispatchers.IO) {
                        try {
                            // 行级流式（压测 10 万行不爆内存的关键）：Connector/J 默认
                            // fetchSize=-1 会把整个 ResultSet 一次性拉进客户端内存；
                            // 正向值（如 1000）则必须配 useCursorFetch=true 连接属性才
                            // 生效。设为 Integer.MIN_VALUE 是 Connector/J 文档的行级流式
                            // 开关 —— rs.next() 逐行从网络读，真正的分批由本方法按
                            // [batchSize] 组批。StarRocks FE 兼容 MySQL 协议，同样适用。
                            statement.fetchSize = Int.MIN_VALUE
                            if (!statement.execute(sql)) {
                                batches.send(
                                    JdbcQueryBatch(updateCount = statement.updateCount.toLong(), isLast = true),
                                )
                                batches.close()
                                return@launch
                            }
                            statement.resultSet.use { resultSet ->
                                val meta = resultSet.metaData
                                val columnCount = meta.columnCount
                                val columns = (1..columnCount).map { meta.getColumnLabel(it) }
                                var rows = ArrayList<List<Any?>>(batchSize)
                                while (resultSet.next()) {
                                    rows += (1..columnCount).map { resultSet.getObject(it) }
                                    if (rows.size >= batchSize) {
                                        batches.send(JdbcQueryBatch(columns, rows, isLast = false))
                                        rows = ArrayList(batchSize)
                                    }
                                }
                                // 尾批（含空结果集的单个空批）。
                                batches.send(JdbcQueryBatch(columns, rows, isLast = true))
                            }
                            batches.close()
                        } catch (error: SQLTimeoutException) {
                            batches.close(JdbcConnectTimeoutException(error.message ?: "query timed out"))
                        } catch (error: SQLException) {
                            batches.close(JdbcFailure(error.message, error.sqlState, error.errorCode, error))
                        } catch (error: Throwable) {
                            batches.close(error)
                        }
                    }
                    try {
                        for (batch in batches) onBatch(batch)
                        completed = true
                    } finally {
                        if (!completed) {
                            // 未自然完成（取消 / 截断 / 异常）：从资源侧强制解开。
                            // 顺序关键：先关 socket（阻断 rs.next() / 防止 statement.close()
                            // 尝试 drain 剩余行而挂住），再关 Statement。
                            producer.cancel()
                            runCatching { raw.close() }   // 流式残留 → 连接报废，交给自愈重建
                            runCatching { statement.close() }
                        } else {
                            // 正常完成（生产协程 use 已关过 Statement；幂等兜底）。
                            runCatching { statement.close() }
                        }
                    }
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
