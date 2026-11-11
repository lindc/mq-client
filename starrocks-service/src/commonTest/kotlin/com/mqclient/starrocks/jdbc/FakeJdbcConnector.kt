package com.mqclient.starrocks.jdbc

/**
 * 测试替身（**commonTest**）：可编程地模拟成功 / 连接超时 / SQL 异常，
 * 并记录 `connect` / `probe` / `close` 调用，供断言「连接用完是否在 finally 中关闭」。
 *
 * 不依赖真实数据库、不触网；[JdbcConnector] 的接口隔离正是为了让它存在。
 */
class FakeJdbcConnector(
    private val behavior: Behavior = Behavior.Success,
) : JdbcConnector {

    /** 三种待模拟的场景。 */
    sealed interface Behavior {
        /** 建连接 + `SELECT 1` 都成功。 */
        data object Success : Behavior

        /** 建连接阶段超时（TCP 握手 / 登录读超时）。 */
        data object ConnectTimeout : Behavior

        /**
         * SQL 异常：[atProbe] = true 时连接建立成功、在 `probe` 阶段抛
         * （模拟 SELECT 1 被拒）；false 时在 `connect` 阶段抛（模拟握手被拒）。
         */
        data class SqlFailure(
            val sqlState: String?,
            val errorCode: Int,
            val message: String,
            val atProbe: Boolean = false,
        ) : Behavior
    }

    /** 已建但尚未关闭的连接数（泄漏检测）。 */
    var openConnections: Int = 0
        private set

    /** `connect()` 调用次数。 */
    var connectCalls: Int = 0
        private set

    /** `probe()` 调用次数。 */
    var probeCalls: Int = 0
        private set

    /** 连接级 `close()` 调用次数（每条连接各一次）。 */
    var connectionCloseCalls: Int = 0
        private set

    /** 连接器级 `close()` 调用次数。 */
    var connectorCloseCalls: Int = 0
        private set

    /**
     * 查询脚本：`executeQuery` 时按序回调的批次（null = 未配置，抛
     * [IllegalStateException]，防测试静默走通未实现的路径）。
     */
    var scriptedQueryBatches: List<JdbcQueryBatch>? = null

    /** `executeQuery` 收到的最后一条 SQL（断言用）。 */
    var lastExecutedSql: String? = null
        private set

    private val connections = mutableListOf<FakeConnection>()

    override suspend fun connect(url: String, user: String, password: String): JdbcConnection {
        connectCalls++
        when (behavior) {
            is Behavior.ConnectTimeout -> throw JdbcConnectTimeoutException("connect timed out")
            is Behavior.SqlFailure -> if (!behavior.atProbe) {
                throw JdbcFailure(behavior.message, behavior.sqlState, behavior.errorCode)
            }
            Behavior.Success -> Unit
        }
        val connection = FakeConnection(behavior)
        connections += connection
        openConnections++
        return connection
    }

    override suspend fun close() {
        connectorCloseCalls++
    }

    private inner class FakeConnection(private val behavior: Behavior) : JdbcConnection {
        @Volatile
        private var closed = false

        override suspend fun probe(): Boolean {
            probeCalls++
            if (behavior is Behavior.SqlFailure && behavior.atProbe) {
                throw JdbcFailure(behavior.message, behavior.sqlState, behavior.errorCode)
            }
            return behavior is Behavior.Success
        }

        override suspend fun executeQuery(
            sql: String,
            batchSize: Int,
            onBatch: suspend (JdbcQueryBatch) -> Unit,
        ) {
            lastExecutedSql = sql
            val batches = scriptedQueryBatches
                ?: throw IllegalStateException("scriptedQueryBatches not configured")
            batches.forEach { onBatch(it) }
        }

        override fun isUsable(): Boolean = !closed

        override suspend fun close() {
            if (closed) return // 幂等
            closed = true
            connectionCloseCalls++
            openConnections--
        }
    }
}
