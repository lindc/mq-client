package com.mqclient.starrocks

import com.mqclient.core.spi.ServiceContext
import com.mqclient.olap.base.connection.AbstractAnalyticsConnectionManager
import com.mqclient.olap.base.connection.HealthReport
import com.mqclient.olap.base.dialect.SqlDialect
import com.mqclient.olap.base.error.OlapErrorCode
import com.mqclient.olap.base.perf.olapTimeMillis
import com.mqclient.olap.base.query.ResultBatch
import com.mqclient.olap.base.query.ResultColumn
import com.mqclient.starrocks.jdbc.JdbcConnection
import com.mqclient.starrocks.jdbc.JdbcConnector
import com.mqclient.starrocks.jdbc.JdbcFailure
import com.mqclient.starrocks.jdbc.StarRocksErrorDetail
import com.mqclient.starrocks.jdbc.StarRocksSqlDialect
import com.mqclient.core.log.LogLevel
import com.mqclient.starrocks.log.StarRocksLog
import com.mqclient.starrocks.tunnel.TunneledConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * StarRocks 连接管理器：真实 `testConnection` / `healthCheck` / 流式查询。
 *
 * 继承 olap-base 的 [AbstractAnalyticsConnectionManager]，复用其「单连接 +
 * Mutex 串行 + EventBus 状态广播 + 半开自愈」骨架；本类只填协议相关的三个钩子：
 * [openSession] / [closeSession] / [checkHealth]，并额外提供一次性探针
 * [testConnection]（新建连接 → `SELECT 1` → finally 关闭）。
 *
 * ## 接口隔离
 *
 * 全部 JDBC 访问经注入的 [JdbcConnector]，**不直接** `DriverManager.getConnection`。
 * 真实实现 `MysqlJdbcConnector`（jvmMain）把 `java.sql.Connection` 适配成
 * [JdbcConnection]；测试在 commonTest 用 `FakeJdbcConnector` 注入，
 * 无需真实数据库。
 *
 * ## 句柄隔离
 *
 * [JdbcConnector] 是连接级共享的重量级对象；[JdbcConnection] / 查询结果都是
 * 轻量句柄，用完即毁（[testConnection] 的 finally 强制关闭）。
 *
 * ## 性能打点
 *
 * 打点走模块统一的 [StarRocksLog.perf]，格式
 * `[MQ-Perf][StarRocks] action=..., type=..., cost=Xms`（与 MQ 系 `[MQ-Perf]` 同口径）。
 * 日志 tag 统一为 `[StarRocks][Conn]` / `[StarRocks][SQL]`（见 [StarRocksLog]）。
 *
 * @param context 模块运行时上下文（EventBus / scope），由 SPI initialize 传入。
 * @param moduleId 模块 id（默认 `starrocks`）。
 * @param connector 注入的 JDBC 连接器。
 * @param dialect 错误分类方言（默认 [StarRocksSqlDialect]）。
 */
class StarRocksConnectionManager(
    context: ServiceContext,
    moduleId: String = StarRocksConstants.PROTOCOL_ID,
    private val connector: JdbcConnector,
    /** SSH 隧道工厂；null = 本平台不支持隧道（此时 sshEnabled 会被忽略并报错）。 */
    private val tunnelFactory: com.mqclient.starrocks.tunnel.ConnectionTunnelFactory? = null,
    private val dialect: SqlDialect = StarRocksSqlDialect,
) : AbstractAnalyticsConnectionManager<StarRocksConnectionConfig, StarRocksSession>(context, moduleId) {

    /**
     * 查询串行锁：行级流式（fetchSize=MIN_VALUE）下，一条物理连接同时只能有
     * 一个活跃 ResultSet。并发收集者在此**挂起排队**（不占线程），而不是撞
     * "Streaming result set is still active" —— 防护下沉到 manager 后，上层
     * 面板（控制台 / 目录 / processlist / 导出 / AI）无需各自加锁。
     */
    private val queryMutex = Mutex()

    /* ------------------------------ 基类钩子 ------------------------------ */

    /**
     * 建立物理连接并包成会话；失败由 [JdbcConnector] 抛平台中立异常。
     *
     * 日志：建立前打端点 / 用户 / **脱敏后的** URL / 超时配置（INFO），成功后补耗时；
     * 失败按「功能中断」处理，打 ERROR 并带完整异常栈（连不上就没有后续任何能力）。
     * 密码永不入日志 —— URL 先过 [StarRocksLog.redactJdbcUrl]，密码本身从不参与拼接。
     */
    override suspend fun openSession(config: StarRocksConnectionConfig): StarRocksSession {
        val start = olapTimeMillis()
        StarRocksLog.conn(
            "connect start endpoint=${config.endpoint} user=${config.user} " +
                "url=${StarRocksLog.redactJdbcUrl(config.jdbcUrl)} timeouts=${timeoutsOf(config.jdbcUrl)}",
        )
        // 隧道必须先开、且失败要连带清理 —— 所以放在 try 外单独一段，成功后再进主 try。
        var tunnel: com.mqclient.starrocks.tunnel.ConnectionTunnel? = null
        return try {
            val url = if (config.sshEnabled) {
                val factory = tunnelFactory
                    ?: throw com.mqclient.starrocks.tunnel.SshUnsupportedException()
                tunnel = factory.open(config)
                // 隧道已就绪：JDBC 连本地回环，其余部分（库名 / 参数）逐字节不变。
                com.mqclient.starrocks.tunnel.rewriteJdbcAuthority(config.jdbcUrl, port = tunnel!!.localPort)
                    ?: throw IllegalStateException("cannot rewrite JDBC URL for tunnel: ${config.jdbcUrl}")
            } else {
                config.jdbcUrl
            }
            val raw: JdbcConnection = connector.connect(url, config.user, config.password)
            // 把隧道挂在连接的关闭路径上：连接关 = 隧道关。
            // 所有权转移：包进 TunneledConnection 后把局部引用置空，
            // 免得 catch 分支重复关（关闭虽幂等，但归属要明确）。
            val connection: JdbcConnection =
                if (tunnel == null) raw else TunneledConnection(raw, tunnel!!).also { tunnel = null }
            StarRocksLog.conn(
                "connected endpoint=${config.endpoint} cost=${olapTimeMillis() - start}ms" +
                    (tunnel?.let { " viaSsh localPort=${it.localPort}" } ?: ""),
            )
            StarRocksLog.perf(action = "connect", type = "SESSION", costMs = olapTimeMillis() - start)
            StarRocksSession(connection, config.endpoint)
        } catch (cancellation: CancellationException) {
            // 取消也要关隧道，否则会留一条 SSH 会话在后台。
            tunnel?.close()
            // 用户中途关掉对话框 / 退出应用：不是失败，记录但不报警。
            StarRocksLog.conn(LogLevel.DEBUG, "connect cancelled endpoint=${config.endpoint}")
            throw cancellation
        } catch (error: Throwable) {
            val code = dialect.classify(error)
            StarRocksLog.conn(
                LogLevel.ERROR,
                "connect failed endpoint=${config.endpoint} user=${config.user} " +
                    "url=${StarRocksLog.redactJdbcUrl(config.jdbcUrl)} " +
                    "code=${code.name} detail=${StarRocksErrorDetail.of(code, error)}",
                error,
            )
            StarRocksLog.perf(action = "connect", type = "SESSION", costMs = olapTimeMillis() - start)
            // 连库失败时隧道必须关掉：否则每次重试都多漏一条 SSH 会话。
            // 成功路径上所有权已转移给 TunneledConnection（tunnel 局部引用置空），
            // 这里只清理「还没挂上连接」的情况。
            tunnel?.close()
            throw error
        }
    }

    /** 关闭会话（[StarRocksSession.close] 已自行兜异常）。 */
    override suspend fun closeSession(session: StarRocksSession) {
        val start = olapTimeMillis()
        StarRocksLog.conn("close session endpoint=${session.endpoint}")
        session.close()
        StarRocksLog.conn("session closed endpoint=${session.endpoint} cost=${olapTimeMillis() - start}ms")
    }

    /**
     * 健康探测本体：在**共享会话**上执行 `SELECT 1`。
     *
     * 失败不抛（基类 `healthCheck` 的 catch 会生成不带错误码的通用失败），
     * 而是就地翻译成 `HealthReport(healthy=false, detail=友好文案+SQLState/ErrorCode)`。
     * 探测可能被周期调用，成功走 DEBUG；失败走 WARN（半开连接是排查重点）。
     */
    override suspend fun checkHealth(session: StarRocksSession): HealthReport {
        val start = olapTimeMillis()
        return try {
            val ok = session.probe()
            val duration = olapTimeMillis() - start
            logProbe("healthCheck", ok, duration, endpoint = session.endpoint)
            HealthReport(
                healthy = ok,
                detail = if (ok) "SELECT 1 ok" else "SELECT 1 returned no rows",
                latencyMs = duration,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val code = dialect.classify(error)
            StarRocksLog.conn(
                LogLevel.WARN,
                "probe failed endpoint=${session.endpoint} code=${code.name} " +
                    "detail=${StarRocksErrorDetail.of(code, error)}",
            )
            StarRocksLog.perf(action = "health_check", type = "SELECT", costMs = olapTimeMillis() - start)
            HealthReport(
                healthy = false,
                detail = StarRocksErrorDetail.of(code, error),
                latencyMs = olapTimeMillis() - start,
            )
        }
    }

    /** 连接级故障（08xxx / 2006 / 2013）→ 让基类 `withSession` 触发自愈。 */
    override fun isSessionBroken(error: Throwable): Boolean = error is JdbcFailure &&
        (error.sqlState?.startsWith("08") == true || error.errorCode == 2006 || error.errorCode == 2013)

    /** 异常 → [OlapErrorCode]（供审计 / UI 文案）。 */
    override fun classifyError(error: Throwable): OlapErrorCode = dialect.classify(error)

    /** 日志里的会话标识（端点，不含凭据）。 */
    override fun describe(session: StarRocksSession): String = session.endpoint

    /* ------------------------------ 连接探针 ------------------------------ */

    /**
     * 一次性连接测试（UI「测试连接」按钮的入口）。
     *
     * 语义等价于任务书的 `HealthReport.Success / Failure`：olap-base 的
     * [HealthReport] 是 `data class(healthy, detail, latencyMs)`（**不是** sealed
     * 类型），故成功 = `healthy=true`，失败 = `healthy=false` + 错误文案。
     *
     * 流程：`connect` → `SELECT 1` → 打耗时点 → 返回报告；**无论成功 / 失败 / 取消，
     * 连接都在 finally 中关闭**（轻量句柄即用即毁）。取消原样上抛，不被
     * `runCatching` 吞掉。
     *
     * 日志：INFO 记端点 / 用户 / 脱敏 URL / 超时配置（表单重复点「测试」时能看出
     * 是哪个连接）；失败走 WARN（抛给用户的业务异常，功能未中断）。
     *
     * 所有耗时操作都是 `suspend`（阻塞 IO 由 `MysqlJdbcConnector` 切到
     * `Dispatchers.IO`），不阻塞主线程。
     *
     * @return 成功时 `healthy=true`；`SQLException` / 超时经
     *   [StarRocksSqlDialect] 映射后返回 `healthy=false` 且 `detail` 含
     *   SQLState / ErrorCode 与友好文案。
     */
    suspend fun testConnection(config: StarRocksConnectionConfig): HealthReport {
        val start = olapTimeMillis()
        var connection: JdbcConnection? = null
        StarRocksLog.conn(
            "test start connectionId=${config.connectionId} endpoint=${config.endpoint} " +
                "user=${config.user} url=${StarRocksLog.redactJdbcUrl(config.jdbcUrl)} " +
                "timeouts=${timeoutsOf(config.jdbcUrl)}",
        )
        return try {
            connection = connector.connect(config.jdbcUrl, config.user, config.password)
            val ok = connection.probe()
            val duration = olapTimeMillis() - start
            logProbe("testConnection", ok, duration, config.connectionId)
            if (ok) {
                HealthReport(healthy = true, detail = "SELECT 1 ok", latencyMs = duration)
            } else {
                HealthReport(healthy = false, detail = "SELECT 1 returned no rows", latencyMs = duration)
            }
        } catch (cancellation: CancellationException) {
            StarRocksLog.conn(
                LogLevel.DEBUG,
                "test cancelled connectionId=${config.connectionId} endpoint=${config.endpoint}",
            )
            throw cancellation
        } catch (error: Throwable) {
            val code = dialect.classify(error)
            val duration = olapTimeMillis() - start
            logProbe("testConnection", false, duration, config.connectionId)
            // 表单里会展示这段提示：业务异常走 WARN，并附带分类与底层诊断字段。
            StarRocksLog.conn(
                LogLevel.WARN,
                "test failed connectionId=${config.connectionId} endpoint=${config.endpoint} " +
                    "code=${code.name} detail=${StarRocksErrorDetail.of(code, error)} " +
                    "cost=${duration}ms errorType=${error::class.simpleName}",
            )
            HealthReport(
                healthy = false,
                detail = StarRocksErrorDetail.of(code, error),
                latencyMs = duration,
            )
        } finally {
            // 轻量句柄即用即毁：成功 / 失败 / 取消都要关，且关不掉不能掩盖真实错误。
            connection?.let { conn ->
                runCatching { conn.close() }
                    .onFailure {
                        StarRocksLog.conn(
                            LogLevel.WARN,
                            "test close failed (ignored) connectionId=${config.connectionId}: ${it.message}",
                        )
                    }
            }
        }
    }

    /** 模块销毁：先关当前会话，再释放连接器（供未来 `ServiceAdapter.dispose` 调用）。 */
    suspend fun shutdown() {
        close()
        runCatching { connector.close() }
    }

    /* ------------------------------ 查询 ------------------------------ */

    /**
     * 在**当前共享会话**上执行一条 SQL，结果按批流式返回。
     *
     * - SELECT：每 [JdbcConnection.DEFAULT_QUERY_BATCH_SIZE] 行一个
     *   [ResultBatch]（schema 每批全量携带，UI 不用回看前序批次）；
     * - DML / DDL：单个 `updateCount != null` 的批次（UI 显示受影响行数）；
     * - `JdbcFailure` / 超时 / 「未连接」（[withSession] 抛
     *   [com.mqclient.core.ServiceException]）原样上抛，UI 用 `userMessage()` 翻译。
     *
     * emit 都发生在调用方上下文（JDBC 阻塞迭代在 connector 内部的 IO 协程），
     * 满足 Flow 上下文不变量；`withSession` 不取锁 —— 长查询不挡 connect /
     * healthCheck（半开自愈语义见基类）。
     *
     * // TODO: 迁移到 olap-base 的 AbstractQueryExecutor（QueryHandle / 取消 /
     *   EXPLAIN / 审计），目前走 manager 级轻量通道。
     */
    fun executeQuery(sql: String, connectionId: String? = null): Flow<ResultBatch> = flow {
        val start = olapTimeMillis()
        var rows = 0L
        var batchIndex = 0
        val kind = StarRocksLog.statementKind(sql)
        // 一条执行只打一对 INFO：开始（含 connectionId + 截断 SQL）与结束（行数 + 耗时）。
        // 不在这里之外重复打 SQL —— 进程列表 10 秒一刷新，三条 INFO 就是刷屏。
        val conn = connectionId?.let { " connection=$it" }.orEmpty()
        StarRocksLog.sql("exec start$conn ${StarRocksLog.sqlSummary(sql)}")
        try {
            // 单槽串行：锁覆盖整个 withSession（含 emit —— 下游消费慢时上游自然停住）。
            queryMutex.withLock {
                withSession { session ->
                    session.executeQuery(sql) { batch ->
                        val result = if (batch.updateCount != null) {
                            ResultBatch(
                                columns = emptyList(),
                                rows = emptyList(),
                                lastBatch = batch.isLast,
                                batchIndex = batchIndex,
                                updateCount = batch.updateCount,
                            )
                        } else {
                            rows += batch.rows.size
                            ResultBatch(
                                columns = batch.columns.map { ResultColumn(it, "") },
                                rows = batch.rows,
                                lastBatch = batch.isLast,
                                batchIndex = batchIndex,
                            )
                        }
                        batchIndex++
                        emit(result)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            StarRocksLog.sql(
                LogLevel.DEBUG,
                "exec cancelled$conn after ${olapTimeMillis() - start}ms rows=$rows batches=$batchIndex",
            )
            throw cancellation
        } catch (error: Throwable) {
            val code = dialect.classify(error)
            // 业务异常（语法错 / 权限 / 表不存在）走 WARN：UI 会展示文案，功能不中断。
            // 连接级故障提升到 ERROR：会话已废，后半条链路全部不可用。
            val level = if (isSessionBroken(error)) LogLevel.ERROR else LogLevel.WARN
            StarRocksLog.sql(
                level,
                "exec failed$conn type=$kind code=${code.name} " +
                    "detail=${StarRocksErrorDetail.of(code, error)} " +
                    "cost=${olapTimeMillis() - start}ms errorType=${error::class.simpleName} " +
                    "sql=${StarRocksLog.truncate(sql)}",
                error,
            )
            StarRocksLog.perf(action = "execute_sql", type = kind, costMs = olapTimeMillis() - start)
            throw error
        }
        val cost = olapTimeMillis() - start
        StarRocksLog.sql("exec done$conn type=$kind rows=$rows batches=$batchIndex cost=${cost}ms")
        StarRocksLog.perf(action = "execute_sql", type = kind, costMs = cost)
    }

    /* ------------------------------ 内部 ------------------------------ */

    /**
     * 探针打点：`[MQ-Perf][StarRocks] action=..., type=SELECT, cost=Xms` + 结果行日志。
     *
     * 探针可能被周期调用（healthCheck），成功走 DEBUG；失败走 WARN。
     */
    private fun logProbe(
        operation: String,
        ok: Boolean,
        durationMs: Long,
        connectionId: String? = null,
        endpoint: String? = null,
    ) {
        StarRocksLog.perf(action = operation, type = "SELECT", costMs = durationMs)
        val context = buildString {
            connectionId?.let { append(" connectionId=").append(it) }
            endpoint?.let { append(" endpoint=").append(it) }
        }
        StarRocksLog.conn(
            if (ok) LogLevel.DEBUG else LogLevel.WARN,
            "probe result=$ok op=$operation$context cost=${durationMs}ms",
        )
    }

    /**
     * 从 JDBC URL 里读出显式超时配置（Connector/J 的 connectTimeout / socketTimeout）。
     *
     * 未配置时返回 `default`：StarRocks 连接一直没有单独的「超时设置」字段，超时只能
     * 通过 `jdbcUrlOverride` 传。日志里把它提出来，是为了排查超时时不必先猜 URL 里写了什么。
     */
    private fun timeoutsOf(url: String): String {
        fun param(name: String): String? =
            Regex("(?i)[?&]" + name + "=([^&]*)").find(url)?.groupValues?.get(1)
        val connect = param("connectTimeout")
        val socket = param("socketTimeout")
        if (connect == null && socket == null) return "default"
        return "connectTimeout=${connect ?: "-"}, socketTimeout=${socket ?: "-"}"
    }
}
