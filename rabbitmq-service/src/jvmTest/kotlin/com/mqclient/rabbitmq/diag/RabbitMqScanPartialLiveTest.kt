package com.mqclient.rabbitmq.diag

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emitSync
import com.mqclient.rabbitmq.RabbitMqAdminClient
import com.mqclient.rabbitmq.RabbitMqConfig
import com.mqclient.rabbitmq.RabbitMqEvents
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.RabbitMqScanService
import com.mqclient.rabbitmq.RabbitMqScanType
import com.mqclient.rabbitmq.isConnectionBroken
import com.mqclient.rabbitmq.rabbitMqConfigFromOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 真机验证：部分扫描失败的隔离（需要 `~/.mqclient/config.toml` 里有可用的 RabbitMQ 连接）。
 *
 * 验证的是「单类失败不把整条连接标成已断开」这条修复在真集群上的行为，与
 * `RabbitMqScanFailureIsolationTest`（离线契约测试）互补 —— 后者证明逻辑，
 * 这里证明**这条连接、这个账号**在真实环境下的表现。
 *
 * 两个层次：
 *  1. 直接探四类管理 API（exchanges / queues / bindings / nodes），打印各自成败。
 *     四类端点的权限要求不同（`/api/nodes` 要 `monitor` tag），这是修复的前提；
 *     若本账号恰好四类全通，那也说明「改动没有破坏正常路径」。
 *  2. 走完整模块链路：初始化 → 连接 → scanObjects → 检查缓存与连接状态。
 *     关键断言：无论第 1 步哪类失败，扫描后连接状态都**不应**变成 Failed。
 *
 * 没有配置 / 没有网络时打印 SKIP 并通过 —— 不让环境问题污染离线 CI。
 */
class RabbitMqScanPartialLiveTest {

    @Test
    fun livePartialScanKeepsConnectionHealthy() = runBlocking {
        val info = ConnectionStore.connections.value.firstOrNull { it.serviceId == "rabbitmq" }
        if (info == null) {
            println("LIVE SKIP: config.toml 里没有 RabbitMQ 连接")
            return@runBlocking
        }
        val config = rabbitMqConfigFromOptions(info.options)
        println("=========== RABBITMQ PARTIAL SCAN DIAG ===========")
        println("  connection = ${info.name} (${info.id})")
        println("  target     = ${config.host}:${config.managementPort} vhost=${config.vhost}")
        println("  user       = ${config.username}")

        // ---- 第 1 步：四类端点各自探测（不经过模块，直看这个账号的权限边界）----
        val probe = LinkedHashMap<String, String>()
        RabbitMqAdminClient(config).use { admin ->
            probe["exchanges"] = probeOf { admin.exchanges().size }
            probe["queues"] = probeOf { admin.queues().size }
            probe["bindings"] = probeOf { admin.bindings().size }
            probe["nodes"] = probeOf { admin.nodes().size }
        }
        println("  --- 端点探测 ---")
        probe.forEach { (kind, result) -> println("  $kind -> $result") }
        val expectedFailures = probe.filterValues { it.startsWith("FAIL") }.keys
        println("  --- 预期失败类型：${if (expectedFailures.isEmpty()) "无（全通）" else expectedFailures} ---")

        // ---- 第 2 步：完整模块链路 ----
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus(scope)
        val context = ServiceContext.of("rabbitmq", bus, scope, JobScheduler(scope, bus))
        RabbitMqModule.initialize(context)
        try {
            context.emitSync(
                RabbitMqEvents.CONNECT_REQUESTED,
                mapOf("connectionId" to info.id),
            )
            val connected = withTimeoutOrNull(60_000) {
                while (RabbitMqModule.connectionOrNull(info.id)?.status != ConnectionStatus.Connected) {
                    delay(200)
                }
                true
            } ?: false
            if (!connected) {
                val reason = RabbitMqModule.connectionOrNull(info.id)?.lastError
                println("LIVE SKIP: 连接未成功（服务器不可达 / 凭据失效）：$reason")
                return@runBlocking
            }
            println("  connect ok: status=${RabbitMqModule.connectionOrNull(info.id)?.status}")

            val service = RabbitMqModule.serviceOrNull()
            assertTrue(service != null, "模块初始化后 serviceOrNull() 不应为 null")

            service.scanObjects(info.id)

            val statusAfter = RabbitMqModule.connectionOrNull(info.id)?.status
            val exchanges = service.cachedExchanges(info.id)
            val queues = service.cachedQueues(info.id)
            val bindings = service.cachedBindings(info.id)
            val nodes = service.cachedNodes(info.id)
            println("  --- scanObjects 结果 ---")
            println("  connection status after scan = $statusAfter")
            println("  cached exchanges=${exchanges.size} queues=${queues.size} bindings=${bindings.size} nodes=${nodes.size}")

            // 核心断言：单类失败（或全通）都不该把连接判成已断开。
            assertTrue(
                statusAfter == ConnectionStatus.Connected,
                "扫描后连接应仍为 Connected；实际 $statusAfter（lastError=${RabbitMqModule.connectionOrNull(info.id)?.lastError}）",
            )
            // 失败的类不写缓存：探测失败的类，缓存应为空（未被空表覆盖的语义在这里表现为「没有写入」）。
            expectedFailures.forEach { kind ->
                val size = when (kind) {
                    "exchanges" -> exchanges.size
                    "queues" -> queues.size
                    "bindings" -> bindings.size
                    "nodes" -> nodes.size
                    else -> -1
                }
                println("  failed kind '$kind' -> cache size=$size（首次扫描时应为 0）")
            }

            // 扫全时提示条数据源必须为空：扫全的清记录与部分失败的记记录是同一条路径。
            val reported = RabbitMqModule.scanFailuresFlow.value[info.id].orEmpty()
            println("  scanFailuresFlow[id] = $reported")
            assertTrue(
                reported.isEmpty(),
                "本轮扫全（或至少没有失败）时不该上报失败类型；实际 $reported",
            )
        } finally {
            RabbitMqModule.shutdown()
        }

        // ---- 第 3 步：用只有 management tag 的账号触发真实的 401 分类 ----
        //
        // 为什么不走 scanObjects：它需要模块里已登记的连接，而登记来自 ConnectionStore
        // （会写用户的 config.toml）。所以这里直接拿真 admin 客户端跑四类端点，把结果
        // 交给与生产同一份判定函数（scanTypeOr / isConnectionBroken / reportScanFailures）。
        //
        // 凭据经 -Drabbitmqdiag.user / -Drabbitmqdiag.pass 传入；不传则跳过。
        probePartialWithLimitedAccount(config.host, config.managementPort)

        println("=========== DIAG DONE ===========")
    }

    /**
     * 用只带 `management` tag 的账号验证部分失败分类。
     *
     * 这个账号在 `/api/nodes` 上会拿 401（"Not monitor user"）—— 正是只读账号的常态，
     * 也正是不该把整条连接判成已断开的那种失败。
     */
    private suspend fun probePartialWithLimitedAccount(host: String, managementPort: Int) {
        val user = System.getProperty("rabbitmqdiag.user")?.takeIf { it.isNotBlank() }
        val pass = System.getProperty("rabbitmqdiag.pass")?.takeIf { it.isNotBlank() }
        if (user == null || pass == null) {
            println("  LIVE SKIP（部分失败分类）: 未传 -Drabbitmqdiag.user / -Drabbitmqdiag.pass")
            return
        }
        println("  --- 受限账号分类（user=$user）---")
        val limited = RabbitMqConfig(host = host, managementPort = managementPort, username = user, password = pass)
        val failures = java.util.concurrent.ConcurrentHashMap<RabbitMqScanType, String>()
        val service = RabbitMqScanService(limitedContext())
        val counts = LinkedHashMap<RabbitMqScanType, Int>()

        RabbitMqAdminClient(limited).use { admin ->
            counts[RabbitMqScanType.EXCHANGES] = service.scanTypeOr(failures, RabbitMqScanType.EXCHANGES, emptyList<Any>()) {
                admin.exchanges()
            }.size
            counts[RabbitMqScanType.QUEUES] = service.scanTypeOr(failures, RabbitMqScanType.QUEUES, emptyList<Any>()) {
                admin.queues()
            }.size
            counts[RabbitMqScanType.BINDINGS] = service.scanTypeOr(failures, RabbitMqScanType.BINDINGS, emptyList<Any>()) {
                admin.bindings()
            }.size
            counts[RabbitMqScanType.NODES] = service.scanTypeOr(failures, RabbitMqScanType.NODES, emptyList<Any>()) {
                admin.nodes()
            }.size
        }

        counts.forEach { (kind, n) -> println("  ${kind.label} -> count=$n") }
        failures.forEach { (kind, msg) -> println("  ${kind.label} -> FAIL: $msg") }

        // 1) 分类正确：刚好是 nodes 失败，其余三类成功（与 connect.txt 里 tag 语义一致）。
        assertEquals(
            setOf(RabbitMqScanType.NODES),
            failures.keys,
            "只有 management tag 时应当恰好只有 nodes 失败；实际 ${failures.keys}",
        )
        assertTrue(counts.getValue(RabbitMqScanType.QUEUES) > 0, "queues 应当仍能读到")

        // 2) 判定正确：单类失败**不**等于连接断开（旧实现会在这里把连接标 Failed）。
        assertTrue(!isConnectionBroken(failures.keys), "单类失败不该判连接已断开")

        // 3) 状态正确：提示条数据源恰好包含 nodes。
        val probeId = "probe-partial"
        try {
            RabbitMqModule.reportScanFailures(probeId, failures.keys.toSet())
            val reported = RabbitMqModule.scanFailuresFlow.value[probeId].orEmpty()
            println("  scanFailuresFlow[probe] = $reported")
            assertEquals(setOf(RabbitMqScanType.NODES), reported)
        } finally {
            RabbitMqModule.clearScanFailures(probeId)
        }
    }

    /** 只为了拿一个 `scanTypeOr` 宿主：这些调用不碰 context。 */
    private fun limitedContext(): ServiceContext {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bus = EventBus(scope)
        return ServiceContext.of("rabbitmq-probe", bus, scope, JobScheduler(scope, bus))
    }

    /** 探测单个端点：返回 `OK(n)` 或 `FAIL(<类型>: <消息>)`。 */
    private suspend fun probeOf(block: suspend () -> Int): String = try {
        "OK(${block()})"
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        "FAIL(${e::class.simpleName}: ${e.message})"
    }
}
