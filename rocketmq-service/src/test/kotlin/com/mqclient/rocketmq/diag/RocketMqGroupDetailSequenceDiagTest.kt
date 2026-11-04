package com.mqclient.rocketmq.diag

import com.mqclient.rocketmq.RocketMqAdminClient
import com.mqclient.rocketmq.RocketMqConfig
import com.mqclient.testing.assumeLive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.system.measureTimeMillis

/**
 * 「开 A → 关 → 开 B → 关 → 再开 A」三次序列的**无界面**复现（默认跳过，实连只读）。
 *
 * 用途：验证 [RocketMqAdminClient.lifecycleState] 里的 `started` / `inFlight` /
 * `admin=#` —— 第三次卡死时到底是实例被 shutdown、还是在途请求堆积。UI 层的
 * 窗口 hide/组合销毁时序无法在这里驱动，所以这里复现的是 **service 层查询序列**
 * + 真实存在的 15s lag 心跳（8 并发 examineConsumeStats，与详情查询共用同一个
 * AdminExt）；GUI 特有的时序仍需在运行中的 app 里看 `mqclient.log`。
 *
 * ```
 * gradle :rocketmq-service:jvmTest --tests "...RocketMqGroupDetailSequenceDiagTest" \
 *   -Drocketmqdiag.enabled=true \
 *   -Drocketmqdiag.namesrv=www.chillbear.top:15672 \
 *   -Drocketmqdiag.sk=... \
 *   -Drocketmqdiag.truststore=C:/Users/.../Documents/data/rocket/ca.crt
 * ```
 */
class RocketMqGroupDetailSequenceDiagTest {

    private fun prop(key: String, default: String = ""): String =
        System.getProperty("rocketmqdiag.$key", default)

    private fun log(message: String) = println("[seq] $message")

    @Test
    fun threeOpenSequence() {
        val sk = prop("sk")
        assumeLive(
            "[seq] skipped（-Drocketmqdiag.enabled=true -Drocketmqdiag.sk=... 打开）",
            prop("enabled").toBoolean() && sk.isNotBlank(),
        )
        val config = RocketMqConfig(
            namesrvAddr = prop("namesrv", "www.chillbear.top:15672"),
            accessKey = prop("ak", "reader-app"),
            secretKey = sk,
            tlsEnabled = prop("tls", "true").toBoolean(),
            tlsVerifyServer = true,
            trustStorePath = prop("truststore", "C:/Users/206630672/Documents/data/rocket/ca.crt"),
        )
        val groupA = prop("groupA", "CG_PROGRESS_DEMO")
        val groupB = prop("groupB", "CG_NO_PROGRESS")
        // 两次打开之间的间隔：给足 >15s 才能让 lag 心跳真的插进来。
        val gapMs = prop("gapMs", "2000").toLong()
        val steps = listOf(groupA, groupB, groupA)
        // 心跳探测组：取日志里实际存在的几个，8 并发与 scanGroupLags 同口径。
        val probeGroups = listOf(
            groupA, groupB, "SELF_TEST_C_GROUP", "TOOLS_CONSUMER",
            "chillbear-test-group", "T_CONSUME_GROUP", "FILTERSRV_CONSUMER", "CID_ONSAPI_PULL",
        )

        val admin = RocketMqAdminClient(config)
        runBlocking {
            admin.startAndVerify()
            log("connected ${admin.lifecycleState()}")

            // 复现真实条件：15s 一次的 lag 心跳，与详情查询共用同一个 AdminExt。
            val heartbeat = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(15_000L)
                    log("heartbeat BEGIN ${admin.lifecycleState()}")
                    coroutineScope {
                        probeGroups.map { g ->
                            async { runCatching { admin.fetchConsumerProgress(g) } }
                        }.awaitAll()
                    }
                    log("heartbeat END   ${admin.lifecycleState()}")
                }
            }

            steps.forEachIndexed { index, group ->
                val n = index + 1
                log("open#$n BEGIN  group=$group ${admin.lifecycleState()}")
                var timedOut = false
                val elapsed = measureTimeMillis {
                    // 与 fetchGroupDetail 相同的三次子查询；15s 对应 UI 的硬上限。
                    timedOut = withTimeoutOrNull(15_000L) {
                        runCatching { admin.fetchGroupConfigOrNull(group) }
                        runCatching { admin.fetchConsumerConnection(group) }
                        runCatching { admin.fetchConsumerProgress(group) }
                        Unit
                    } == null
                }
                log("open#$n END    group=$group ${elapsed}ms timedOut=$timedOut ${admin.lifecycleState()}")
                // 模拟「关闭 → 再打开另一个组」的间隔。
                delay(gapMs)
                log("open#$n CLOSED ${admin.lifecycleState()}")
            }

            heartbeat.cancel()
            admin.close()
            log("done ${admin.lifecycleState()}")
        }
    }
}
