package com.mqclient.rabbitmq.diag

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emitSync
import com.mqclient.rabbitmq.RabbitMqAdminClient
import com.mqclient.rabbitmq.RabbitMqEvents
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.rabbitMqConfigFromOptions
import kotlin.test.Test
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
        } finally {
            RabbitMqModule.shutdown()
        }
        println("=========== DIAG DONE ===========")
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
