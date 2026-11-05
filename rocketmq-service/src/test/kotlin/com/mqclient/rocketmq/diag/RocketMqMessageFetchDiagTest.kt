package com.mqclient.rocketmq.diag

import com.mqclient.rocketmq.RocketMqAdminClient
import com.mqclient.rocketmq.RocketMqConfig
import com.mqclient.rocketmq.RocketMqMessageClients
import com.mqclient.testing.assumeLive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.rocketmq.client.consumer.PullStatus
import kotlin.system.measureTimeMillis
import kotlin.test.Test

/**
 * 消息拉取网络段的无界面复现（默认跳过，实连只读）。
 *
 * [com.mqclient.rocketmq.RocketMqMessageService.readMessages] 的 UI 入口需要模块
 * 连接上下文（[com.mqclient.rocketmq.RocketMqModule]），diag 无法轻量触达；本测试
 * 用**同一个** [RocketMqMessageClients]（与 readMessages 完全相同的共享实例获取
 * 路径）复刻其网络 IO 段 —— fetchSubscribeMessageQueues / maxOffset / pull(5s)。
 *
 * 两轮拉取直接回答任务 2 的核心疑点：
 *  - round1 instanceMs = 新建 PullConsumer + start 的建连耗时（一次性的）
 *  - round2 instanceMs ≈ 0 = 共享实例复用（若第二轮仍很大 = 每次都在新建，是 bug）
 *
 * ```
 * gradle :rocketmq-service:jvmTest --tests "...RocketMqMessageFetchDiagTest" \
 *   -Drocketmqdiag.enabled=true -Drocketmqdiag.sk=... \
 *   -Drocketmqdiag.truststore=... [-Drocketmqdiag.topic=...]
 * ```
 */
class RocketMqMessageFetchDiagTest {

    private fun prop(key: String, default: String = ""): String =
        System.getProperty("rocketmqdiag.$key", default)

    private fun log(message: String) = println("[MQ-Perf][RocketMQ][diag] $message")

    @Test
    fun fetchWithSharedClient() {
        val sk = prop("sk")
        assumeLive(
            "[MQ-Perf][diag] skipped（-Drocketmqdiag.enabled=true -Drocketmqdiag.sk=... 打开）",
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
        val clients = RocketMqMessageClients(config)
        val admin = RocketMqAdminClient(config)
        runBlocking {
            admin.startAndVerify()
            val candidates = admin.fetchTopicList()
                .filter {
                    !it.startsWith("%") && !it.startsWith("TBW") &&
                        !it.startsWith("SELF_TEST") && !it.startsWith("OFFSET") &&
                        !it.startsWith("RMQ_SYS") && !it.startsWith("CID_") &&
                        !it.startsWith("TOOLS") && !it.startsWith("FILTER") &&
                        !it.startsWith("DEFAULT") && !it.startsWith("SCHEDULE")
                }
            val topic = prop("topic").ifBlank { candidates.firstOrNull() ?: "" }
            log("candidates=${candidates.size}, chosen='$topic'")
            repeat(2) { round ->
                val roundStart = System.currentTimeMillis()
                // ---- 实例获取（与 readMessages 同一条路径：懒建共享 PullConsumer）----
                val instanceMs = measureTimeMillis { clients.pullConsumer() }
                log(
                    "获取/初始化客户端实例耗时=${instanceMs}ms round=$round" +
                        if (instanceMs > 100) " (新建 + start 建连)" else " (共享实例复用)",
                )
                withContext(Dispatchers.IO) {
                    clients.access.withLock {
                        val consumer = clients.pullConsumer()
                        if (topic.isBlank()) {
                            log("no topic available, skip pull")
                            return@withLock
                        }
                        val queues = runCatching {
                            consumer.fetchSubscribeMessageQueues(topic).toList()
                        }.getOrElse {
                            log("fetchSubscribeMessageQueues failed: topic=$topic, 类型=${it::class.simpleName}, 详情=${it.message}")
                            return@withLock
                        }
                        var ioMs = 0L
                        var found = 0
                        for (mq in queues) {
                            val max = runCatching { consumer.maxOffset(mq) }.getOrDefault(-1L)
                            val begin = (max - 20).coerceAtLeast(0L)
                            val t = System.currentTimeMillis()
                            val result = consumer.pull(mq, "*", begin, 20, 5_000L)
                            ioMs += System.currentTimeMillis() - t
                            if (result.pullStatus == PullStatus.FOUND) {
                                found += result.msgFoundList.orEmpty().size
                            }
                        }
                        log(
                            "pull done: topic=$topic, queues=${queues.size}, ioMs=$ioMs, " +
                                "messages=$found, roundTotalMs=${System.currentTimeMillis() - roundStart}, round=$round",
                        )
                    }
                }
            }
            clients.shutdown()
            admin.close()
        }
    }
}
