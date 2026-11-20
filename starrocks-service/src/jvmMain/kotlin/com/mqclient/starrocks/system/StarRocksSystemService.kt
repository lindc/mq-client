package com.mqclient.starrocks.system

import com.mqclient.core.log.LogLevel
import com.mqclient.olap.base.query.ResultColumn
import com.mqclient.starrocks.StarRocksModule
import com.mqclient.starrocks.cluster.indexOfAny
import com.mqclient.starrocks.cluster.parseBool
import com.mqclient.starrocks.cluster.textAt
import com.mqclient.starrocks.log.StarRocksLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 系统信息取数门面（Service 层）：变量 / FE 配置走 SQL，FE 日志走 Web 端口。
 *
 * 与 [com.mqclient.starrocks.cluster.StarRocksClusterService] 同一取向：
 * 要发哪些 SQL、结果怎么归类、失败怎么降级全部收在 service，UI 只负责排版。
 *
 * ## 日志为什么要单独一条通道
 *
 * backends / variables / ha 都有等价 SQL，日志没有（`SHOW PROC '/log'` 在 3.4.0
 * 不存在）。FE 只通过 Web 端口暴露 `GET /log?file=…`（Basic Auth + HTML）。
 * 于是这里多了一个 HTTP 分支，但**凭据来源仍是连接配置**，用户不需要为日志
 * 再填一次账号密码。
 *
 * 端口按 [feHttpPortCandidates] 的顺序逐个试：先问 `SHOW FRONTENDS` 的 `HttpPort`
 * （集群真实配置），再退到连接配置与 `queryPort - 1000`。任何一个端口返回 200 即停。
 */
object StarRocksSystemService {

    /** 变量分两种作用域，与服务端语句一一对应。 */
    enum class VariableScope { SESSION, GLOBAL }

    private fun variableSql(scope: VariableScope): String = when (scope) {
        VariableScope.SESSION -> "SHOW VARIABLES;"
        VariableScope.GLOBAL -> "SHOW GLOBAL VARIABLES;"
    }

    private const val SQL_FE_CONFIG = "ADMIN SHOW FRONTEND CONFIG;"

    private const val SQL_FRONTENDS = "SHOW FRONTENDS;"

    /** 一次查询的原始结果（列名 + 行）。 */
    private data class Rows(val columns: List<String>, val rows: List<List<Any?>>)

    /** 走共享会话的只读查询（与面板其它取数同一条通道）。 */
    private suspend fun query(connectionId: String, sql: String): Rows {
        if (!StarRocksModule.isAlive(connectionId)) {
            StarRocksModule.connectById(connectionId).getOrThrow()
        }
        var columns: List<ResultColumn> = emptyList()
        val rows = mutableListOf<List<Any?>>()
        StarRocksModule.executeQuery(connectionId, sql).collect { batch ->
            if (batch.columns.isNotEmpty()) columns = batch.columns
            rows.addAll(batch.rows)
        }
        return Rows(columns.map { it.name }, rows)
    }

    /**
     * 会话 / 全局变量。
     *
     * `SHOW GLOBAL VARIABLES` 需要权限，普通账号可能被拒 —— 失败原样上抛，
     * 由 UI 决定显示错误还是提示切换作用域（`SHOW VARIABLES` 谁都看得到）。
     */
    suspend fun loadVariables(
        connectionId: String,
        scope: VariableScope = VariableScope.SESSION,
    ): List<SessionVariable> {
        val sql = variableSql(scope)
        val start = System.currentTimeMillis()
        return try {
            val rows = query(connectionId, sql)
            val parsed = parseVariables(rows.columns, rows.rows)
            val cost = System.currentTimeMillis() - start
            StarRocksLog.sql("system variables scope=${scope.name} rows=${parsed.size} cost=${cost}ms")
            StarRocksLog.perf(action = "load_variables", type = scope.name, costMs = cost)
            parsed
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // 权限不足是最常见的失败原因：WARN，并指出是哪个作用域。
            StarRocksLog.sql(
                LogLevel.WARN,
                "system variables failed scope=${scope.name} " +
                    "cost=${System.currentTimeMillis() - start}ms error=${error.message}",
                error,
            )
            throw error
        }
    }

    /** FE 配置项（`ADMIN SHOW FRONTEND CONFIG`，需要管理员权限）。 */
    suspend fun loadFeConfig(connectionId: String): List<FeConfigEntry> {
        val start = System.currentTimeMillis()
        return try {
            val rows = query(connectionId, SQL_FE_CONFIG)
            val parsed = parseFeConfig(rows.columns, rows.rows)
            val cost = System.currentTimeMillis() - start
            StarRocksLog.sql("system fe config rows=${parsed.size} cost=${cost}ms")
            StarRocksLog.perf(action = "load_fe_config", type = "CONFIG", costMs = cost)
            parsed
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            StarRocksLog.sql(
                LogLevel.WARN,
                "system fe config failed cost=${System.currentTimeMillis() - start}ms error=${error.message}",
                error,
            )
            throw error
        }
    }

    /**
     * 拉取 FE 日志正文（已裁剪到尾部 [maxLines] 行）。
     *
     * 失败分类（决定 UI 提示什么）：
     *  - **所有候选端口都连不上** → [FeLogUnavailableException]，附带试过的端口；
     *  - **401** → 凭据被拒（连接配置里的账号密码对 Web 端口不生效）：
     *    [FeLogAuthException]；
     *  - 其它 HTTP 状态 → [FeLogUnavailableException]，带状态码。
     */
    suspend fun loadFeLog(
        connectionId: String,
        file: FeLogFile = FeLogFile.WARN,
        maxLines: Int = 2000,
        http: FeLogHttp = DefaultFeLogHttp,
    ): FeLogResult = withContext(Dispatchers.IO) {
        val config = StarRocksModule.configOrNull(connectionId)
            ?: throw FeLogUnavailableException("connection '$connectionId' not found", emptyList())

        // 端口发现：SHOW FRONTENDS 的 HttpPort 是唯一能反映集群真实配置的来源。
        // 失败不致命（可能是权限 / 老版本），继续走候选列表 —— 但**取消必须上抛**：
        // runCatching 会把 CancellationException 一起吞掉，导致用户切走面板后
        // 这段仍然继续发 HTTP 请求（最长几十秒）。
        val discovered = try {
            feHttpPort(connectionId, config.host)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (error is Error) throw error
            StarRocksLog.sql(
                LogLevel.DEBUG,
                "fe log: http port discovery failed, falling back to candidates (${error.message})",
            )
            null
        }

        val candidates = feHttpPortCandidates(
            discovered = discovered,
            configured = null,
            queryPort = config.port,
        )
        StarRocksLog.sql(
            "fe log fetch file=${file.fileName} candidates=$candidates host=${config.host}",
        )

        val start = System.currentTimeMillis()
        var lastError: Throwable? = null
        for (port in candidates) {
            try {
                val html = http.get(
                    url = "http://${config.host}:$port/log?file=${file.fileName}",
                    user = config.user,
                    password = config.password,
                )
                val text = tailLines(extractLogText(html), maxLines)
                val cost = System.currentTimeMillis() - start
                StarRocksLog.sql(
                    "fe log fetched file=${file.fileName} port=$port chars=${text.length} cost=${cost}ms",
                )
                StarRocksLog.perf(action = "load_fe_log", type = file.name, costMs = cost)
                return@withContext FeLogResult(text = text, port = port)
            } catch (auth: FeLogAuthException) {
                // 401 与端口无关：换端口也不会好，直接抛（避免为一个明确的凭据问题试三轮）。
                StarRocksLog.sql(LogLevel.WARN, "fe log auth rejected port=$port file=${file.fileName}")
                throw auth
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                // Error（OOM 等）不参与端口重试：换一个端口只会把同一次分配再做一遍。
                if (error is Error) throw error
                lastError = error
                StarRocksLog.sql(
                    LogLevel.DEBUG,
                    "fe log candidate port=$port failed: ${error.message}",
                )
            }
        }
        throw FeLogUnavailableException(
            message = lastError?.message ?: "no FE HTTP port reachable",
            triedPorts = candidates,
            cause = lastError,
        )
    }

    /**
     * 从 `SHOW FRONTENDS` 找 Web 端口。
     *
     * 优先取与连接 host 相同的那台；找不到（连接用的是域名而集群里是 IP）就取
     * 第一台存活 FE —— 同一集群的 FE 之间 HttpPort 通常一致，拿错最多是端口不同，
     * 上层还有候选兜底。
     */
    private suspend fun feHttpPort(connectionId: String, host: String): Int? {
        val rows = query(connectionId, SQL_FRONTENDS)
        val ipIdx = indexOfAny(rows.columns, "ip", "host")
        val httpIdx = indexOfAny(rows.columns, "httpport")
        val aliveIdx = indexOfAny(rows.columns, "alive", "isalive")
        if (ipIdx == null || httpIdx == null) return null

        fun rowIp(row: List<Any?>): String? = row.textAt(ipIdx)
        fun rowPort(row: List<Any?>): Int? = row.textAt(httpIdx)?.toIntOrNull()
        fun rowAlive(row: List<Any?>): Boolean =
            aliveIdx == null || parseBool(row.textAt(aliveIdx))

        return rows.rows.firstOrNull { rowIp(it) == host }?.let(::rowPort)
            ?: rows.rows.firstOrNull { rowAlive(it) }?.let(::rowPort)
    }
}

/** 拉取结果：正文 + 实际生效的端口（显示在工具条上，排障时有用）。 */
data class FeLogResult(val text: String, val port: Int)

/** FE Web 端口不可达（含所有候选都失败）。 */
class FeLogUnavailableException(
    message: String,
    val triedPorts: List<Int>,
    cause: Throwable? = null,
) : Exception(message, cause)

/** FE Web 端口拒绝凭据（401）。 */
class FeLogAuthException(message: String) : Exception(message)

/**
 * HTTP 取数的最小接缝（便于无网络单测替换）。
 *
 * 刻意不引入 Ktor：只需要一个 GET + Basic Auth，`HttpURLConnection` 足够，
 * 且 starrocks-service 目前没有任何 HTTP 客户端依赖 —— 为这一个请求加一整套
 * 客户端会把模块的依赖面撑大。
 */
fun interface FeLogHttp {
    /** @return 响应正文；非 200 抛 [FeLogAuthException] / [FeLogUnavailableException]。 */
    fun get(url: String, user: String, password: String): String
}

/** 默认实现：`HttpURLConnection` + Basic Auth，超时固定（日志页不该卡住界面）。 */
object DefaultFeLogHttp : FeLogHttp {

    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 8000

    /**
     * 只保留响应正文的最后这么多字节。
     *
     * FE 的 `/log` 不支持 Range 也不支持分页（实测：带 `Range: bytes=-2000` 仍返回
     * 200 + 完整文件），而 FE 的日志轮转阈值默认 200MB —— 把整份读进内存再截取，
     * 峰值约有 3 倍文件大小的分配（byte[] → String → 按行切分），大日志下直接 OOM。
     *
     * 1MB 足够容纳 2000 行（单行 500 字节），也正是面板要展示的量。
     */
    private const val MAX_LOG_TAIL_BYTES = 1024 * 1024

    override fun get(url: String, user: String, password: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            val token = Base64.getEncoder()
                .encodeToString("$user:$password".toByteArray(StandardCharsets.UTF_8))
            connection.setRequestProperty("Authorization", "Basic $token")
            val status = connection.responseCode
            when {
                status == HttpURLConnection.HTTP_OK -> {
                    connection.inputStream.use { stream ->
                        readTailText(stream, MAX_LOG_TAIL_BYTES)
                    }
                }
                status == HttpURLConnection.HTTP_UNAUTHORIZED -> throw FeLogAuthException(
                    "FE web rejected the credentials (HTTP 401)",
                )
                else -> throw FeLogUnavailableException(
                    message = "FE web returned HTTP $status",
                    triedPorts = emptyList(),
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 流式读取响应，只保留最后 [maxBytes] 字节（滑动窗口）。
     *
     * 为什么不是 `readBytes()` 再 `takeLast`：那正是要避免的 OOM 形态。这里读一块、
     * 丢一块，常驻内存恒定为 [maxBytes] + 一个块。
     *
     * UTF-8 多字节字符可能被窗口边界切开 —— 解码时用 `REPLACE` 容错，代价是窗口
     * 起点处最多一个替换字符；日志尾部（用户实际要看的部分）不受影响。
     */
    internal fun readTailText(stream: java.io.InputStream, maxBytes: Int): String {
        val chunk = ByteArray(64 * 1024)
        var window = ByteArray(0)
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            if (read == 0) continue
            if (read >= maxBytes) {
                window = chunk.copyOf(read).copyOfRange(read - maxBytes, read)
                continue
            }
            val keep = (window.size + read - maxBytes).coerceAtLeast(0)
            val next = ByteArray(window.size - keep + read)
            System.arraycopy(window, keep, next, 0, window.size - keep)
            System.arraycopy(chunk, 0, next, window.size - keep, read)
            window = next
        }
        return String(window, StandardCharsets.UTF_8)
    }
}
