package com.mqclient.starrocks.system

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionRegistry
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import com.mqclient.starrocks.StarRocksConnectionConfig
import com.mqclient.starrocks.StarRocksModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **真机**端到端校验：四个系统页的数据源在本地 StarRocks 3.4 上确实可用。
 *
 * 与仓库其它 `*LiveTest` 同一条约定：**环境不就绪就 SKIP**（`return` 而不是 fail），
 * 所以没有本地 StarRocks 的 CI 不会因此变红。开启条件：
 *
 * ```
 * ./gradlew :starrocks-service:jvmTest --tests '*SystemLiveTest' \
 *   -Dstarrocks.live.enabled=true -Dstarrocks.live.password=...
 * ```
 *
 * 检查的是「SQL / HTTP 通道 + 解析器」这一整段，不是 UI 排版 —— 排版由画布测试管。
 */
class StarRocksSystemLiveTest {

    private val enabled = System.getProperty("starrocks.live.enabled") == "true"
    private val host = System.getProperty("starrocks.live.host") ?: "127.0.0.1"
    private val port = System.getProperty("starrocks.live.port")?.toIntOrNull() ?: 9030
    private val user = System.getProperty("starrocks.live.user") ?: "root"
    private val password = System.getProperty("starrocks.live.password") ?: ""

    private val connectionId = "live-starrocks"

    /** FE Web 端口（`/log` 用）；默认 8030，可用 -Dstarrocks.live.httpPort 覆盖。 */
    private val httpPort = System.getProperty("starrocks.live.httpPort")?.toIntOrNull() ?: 8030

    /**
     * 每个用例结束后摘掉模块门面：`StarRocksModule` 是进程级单例，不 dispose 会把
     * 管理器和 JDBC 会话留给同一 JVM 里的其它用例（真实泄漏，不是洁癖）。
     */
    @AfterTest
    fun tearDown() {
        runCatching { StarRocksModule.dispose() }
    }

    private fun installModule() {
        val scope = CoroutineScope(SupervisorJob())
        val bus = EventBus(scope)
        val config = StarRocksConnectionConfig(
            connectionId = connectionId,
            displayName = "live",
            host = host,
            port = port,
            user = user,
            password = password,
            database = "test_db",
        )
        val info = ConnectionInfo(
            id = connectionId,
            name = "live",
            serviceId = "starrocks",
            mqName = "",
            host = host,
            port = port,
            options = config.options,
        )
        val registry = object : ConnectionRegistry {
            private val state = MutableStateFlow(listOf(info))
            override val connections: StateFlow<List<ConnectionInfo>> = state.asStateFlow()
            override fun findConnection(connectionId: String): ConnectionInfo? =
                info.takeIf { it.id == connectionId }
            override fun reportStatus(connectionId: String, status: ConnectionStatus, source: String) = true
        }
        StarRocksModule.initialize(ServiceContext.of("starrocks", bus, scope, JobScheduler(scope, bus), registry))
    }

    @Test
    fun `session variables are readable and parsed`() = runBlocking {
        if (!enabled) return@runBlocking
        installModule()
        val vars = StarRocksSystemService.loadVariables(
            connectionId,
            StarRocksSystemService.VariableScope.SESSION,
        )
        println("LIVE variables=${vars.size} sample=${vars.take(3)}")
        assertTrue(vars.size > 50, "3.4 的会话变量有 286 个，实际 ${vars.size}")
        assertTrue(vars.any { it.name == "autocommit" }, "应包含 autocommit")
    }

    @Test
    fun `global variables are readable and parsed`() = runBlocking {
        if (!enabled) return@runBlocking
        installModule()
        val vars = StarRocksSystemService.loadVariables(
            connectionId,
            StarRocksSystemService.VariableScope.GLOBAL,
        )
        println("LIVE globalVariables=${vars.size}")
        assertTrue(vars.isNotEmpty())
    }

    @Test
    fun `fe config is readable and carries the mutable flag`() = runBlocking {
        if (!enabled) return@runBlocking
        installModule()
        val config = StarRocksSystemService.loadFeConfig(connectionId)
        println("LIVE feConfig=${config.size} sample=${config.take(3)}")
        assertTrue(config.isNotEmpty(), "ADMIN SHOW FRONTEND CONFIG 应有结果")
        assertTrue(config.any { it.isMutable == true }, "至少有一项可在线修改")
        assertTrue(config.any { it.comment.isNotBlank() }, "至少有一项带注释")
    }

    @Test
    fun `fe log is fetched through the web port`() = runBlocking {
        if (!enabled) return@runBlocking
        installModule()
        val result = StarRocksSystemService.loadFeLog(connectionId, FeLogFile.WARN)
        println("LIVE feLog port=${result.port} chars=${result.text.length}")
        println(result.text.lineSequence().take(3).joinToString("\n"))
        assertTrue(result.text.isNotEmpty(), "fe.warn.log 应有内容")
        assertTrue(result.port in 1..65535)
        assertTrue(!result.text.contains("<pre>"), "HTML 必须已被剥离")
    }

    @Test
    fun `ha extras and backends are readable from the live cluster`() = runBlocking {
        if (!enabled) return@runBlocking
        installModule()
        // backends / ha 两个面板复用 StarRocksClusterService 的快照：这里验证真机下
        // 角色判定与 HA 附加字段（回放日志 / 是否已加入）确实拿得到，不是恒为 null。
        val snapshot = com.mqclient.starrocks.cluster.StarRocksClusterService.loadSnapshot(connectionId)
        val frontends = snapshot.nodes.filter { it.role.isFrontend }
        val computes = snapshot.nodes.filter { it.role.isCompute }
        val summary = haSummary(frontends)
        println("LIVE ha leader=${summary.leaderName} electable=${summary.electableAlive}/${summary.electableTotal} " +
            "observers=${summary.observerTotal} quorum=${summary.quorumHealthy}")
        frontends.forEach { println("LIVE fe ${it.name} role=${it.role} extra=${it.extra}") }
        computes.forEach { println("LIVE be ${it.name} alive=${it.alive} tablets=${it.tabletCount} disk=${it.diskTotalBytes}") }

        assertTrue(frontends.isNotEmpty(), "应至少有一个 FE")
        assertTrue(summary.quorumHealthy, "单机 FE 也应满足多数派")
        assertTrue(frontends.any { it.extra["replayed_journal_id"] != null }, "回放日志 ID 必须解析出来")
        assertTrue(frontends.any { it.extra["role"] != null }, "原始 Role 列必须解析出来")
        assertTrue(computes.isNotEmpty(), "应至少有一个 BE")
        assertTrue(computes.all { it.tabletCount != null }, "BE 的分片数应收上来")
    }

    @Test
    fun `wrong password is reported as an auth failure not a port failure`() = runBlocking {
        if (!enabled) return@runBlocking
        installModule()
        // 直接打 HTTP 层：证明 401 被识别成 FeLogAuthException（UI 据此提示改凭据）。
        val http = DefaultFeLogHttp
        val error = runCatching {
            http.get("http://$host:$httpPort/log?file=fe.warn.log", user, "definitely-wrong-password")
        }.exceptionOrNull()
        println("LIVE wrongPassword -> ${error?.let { it::class.simpleName }} ${error?.message}")
        assertTrue(error is FeLogAuthException, "错误密码必须映射成 FeLogAuthException，实际 $error")
    }
}
