package com.mqclient.rocketmq.diag

import com.mqclient.rocketmq.RocketMqAdminClient
import com.mqclient.rocketmq.RocketMqConfig
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import kotlin.test.Test

/**
 * RocketMQ 实连诊断（默认跳过，照 kafka-service 的 `KafkaMessageDiagTest` 同一套
 * 「-D 开关、默认不触网」约定）。跑一次 = 「建临时连接验证 + 拉全量 topic + 关闭」
 * 循环两遍 —— 第二遍覆盖「上一个 AdminExt 关闭后再建」的路径。
 *
 * ```
 * gradle :rocketmq-service:jvmTest --tests "...RocketMqConnectionDiagTest" \
 *   -Drocketmqdiag.enabled=true \
 *   -Drocketmqdiag.namesrv=www.chillbear.top:15672 \
 *   -Drocketmqdiag.sk=reader123456 \
 *   -Drocketmqdiag.truststore=/home/app/Documents/mq/rocketmq/ca.crt
 * ```
 *
 * ak 默认 `reader-app`（只读）；sk 必须显式给，避免凭证进仓库。
 * jvmTest 工作目录是模块目录，truststore / 证书一律绝对路径。
 */
class RocketMqConnectionDiagTest {

    private fun prop(key: String, default: String = ""): String =
        System.getProperty("rocketmqdiag.$key", default)

    @Test
    fun connectAndProbe() {
        val sk = prop("sk")
        if (!prop("enabled").toBoolean() || sk.isBlank()) {
            println("[rocketmqdiag] skipped（-Drocketmqdiag.enabled=true -Drocketmqdiag.sk=... 打开）")
            return
        }
        val config = RocketMqConfig(
            namesrvAddr = prop("namesrv", "www.chillbear.top:15672"),
            accessKey = prop("ak", "reader-app"),
            secretKey = sk,
            tlsEnabled = prop("tls", "true").toBoolean(),
            tlsVerifyServer = true,
            trustStorePath = prop("truststore", "/home/app/Documents/mq/rocketmq/ca.crt"),
        )
        println("[rocketmqdiag] namesrv=${config.namesrvAddr} tls=${config.tlsEnabled} ak=${config.accessKey}")

        repeat(2) { round ->
            val elapsed = measureTimeMillis {
                RocketMqAdminClient(config).use { admin ->
                    runBlocking {
                        admin.startAndVerify()
                        val topics = admin.fetchTopicList()
                        println("[rocketmqdiag] round${round + 1}: verify OK, topics=${topics.size} sample=${topics.take(8)}")
                    }
                }
            }
            println("[rocketmqdiag] round${round + 1}: ${elapsed}ms")
        }
        println("[rocketmqdiag] done")
    }
}
