package com.mqclient.starrocks

import com.mqclient.core.spi.ServiceContext
import com.mqclient.olap.base.OlapCategory
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * StarRocks 连接管理器（S1：真实 `testConnection` 与 `healthCheck`）。
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
 * 与 `[MQ-Perf]` 同口径输出 `[OLAP-Perf][connect]`（前缀取
 * [OlapCategory.PERF_TAG_PREFIX]，不在本模块硬编码），字段含 connection /
 * 耗时=Xms / result。
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
    private val dialect: SqlDialect = StarRocksSqlDialect,
) : AbstractAnalyticsConnectionManager<StarRocksConnectionConfig, StarRocksSession>(context, moduleId) {

    /** 模块日志句柄（tag = `[OLAP]<moduleId>`，前缀来自 olap-base 常量）。 */
    private val logTag: String = "${OlapCategory.LOG_TAG_PREFIX}$moduleId"

    /** 打点前缀（取 olap-base 常量，禁止硬编码）。 */
    private val perfPrefix: String = OlapCategory.PERF_TAG_PREFIX

    /* ------------------------------ 基类钩子 ------------------------------ */

    /** 建立物理连接并包成会话；失败由 [JdbcConnector] 抛平台中立异常。 */
    override suspend fun openSession(config: StarRocksConnectionConfig): StarRocksSession {
        val connection: JdbcConnection = connector.connect(config.jdbcUrl, config.user, config.password)
        return StarRocksSession(connection, config.endpoint)
    }

    /** 关闭会话（[StarRocksSession.close] 已自行兜异常）。 */
    override suspend fun closeSession(session: StarRocksSession) {
        session.close()
    }

    /**
     * 健康探测本体：在**共享会话**上执行 `SELECT 1`。
     *
     * 失败不抛（基类 `healthCheck` 的 catch 会生成不带错误码的通用失败），
     * 而是就地翻译成 `HealthReport(healthy=false, detail=友好文案+SQLState/ErrorCode)`。
     */
    override suspend fun checkHealth(session: StarRocksSession): HealthReport {
        val start = olapTimeMillis()
        return try {
            val ok = session.probe()
            val duration = olapTimeMillis() - start
            logProbe("healthCheck", ok, duration)
            HealthReport(
                healthy = ok,
                detail = if (ok) "SELECT 1 ok" else "SELECT 1 returned no rows",
                latencyMs = duration,
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val code = dialect.classify(error)
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

    /* ------------------------------ S1 公开 API ------------------------------ */

    /**
     * 一次性连接测试（UI「测试连接」按钮的入口）。
     *
     * 语义等价于任务书的 `HealthReport.Success / Failure`：olap-base 的
     * [HealthReport] 是 `data class(healthy, detail, latencyMs)`（**不是** sealed
     * 类型），故成功 = `healthy=true`，失败 = `healthy=false` + 错误文案。
     *
     * 流程：`connect` → `SELECT 1` → 打 `[OLAP-Perf][connect]` 耗时日志 →
     * 返回报告；**无论成功 / 失败 / 取消，连接都在 finally 中关闭**（轻量句柄
     * 即用即毁）。取消原样上抛，不被 `runCatching` 吞掉。
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
            throw cancellation
        } catch (error: Throwable) {
            val code = dialect.classify(error)
            val duration = olapTimeMillis() - start
            logProbe("testConnection", false, duration, config.connectionId)
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
                        com.mqclient.core.log.Log.warn(
                            logTag,
                            "testConnection: connection close failed (ignored): ${it.message}",
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

    /* ------------------------------ S3 查询 ------------------------------ */

    /**
     * 在**当前共享会话**上执行一条 SQL，结果按批流式返回（MVP 查询闭环）。
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
     *   EXPLAIN / 审计），MVP 先走 manager 级轻量通道。
     */
    fun executeQuery(sql: String): Flow<ResultBatch> = flow {
        val start = olapTimeMillis()
        var rows = 0L
        var batchIndex = 0
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
        com.mqclient.core.log.Log.info(
            logTag,
            "$perfPrefix[query] rows=$rows 耗时=${olapTimeMillis() - start}ms",
        )
    }

    /* ------------------------------ 内部 ------------------------------ */

    /** `[OLAP-Perf][connect]` 分段日志（耗时单位 ms，字段与 MQ-Perf 对齐）。 */
    private fun logProbe(
        operation: String,
        ok: Boolean,
        durationMs: Long,
        connectionId: String? = null,
    ) {
        val conn = connectionId?.let { " connection=$it" } ?: ""
        com.mqclient.core.log.Log.info(
            logTag,
            "$perfPrefix[connect]$conn op=$operation result=$ok 耗时=${durationMs}ms",
        )
    }
}
