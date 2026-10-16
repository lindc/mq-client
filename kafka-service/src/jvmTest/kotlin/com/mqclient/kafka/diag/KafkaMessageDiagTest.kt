package com.mqclient.kafka.diag

import com.mqclient.kafka.KafkaConfig
import com.mqclient.kafka.KafkaMessageHeader
import com.mqclient.kafka.KafkaMessageService
import com.mqclient.kafka.KafkaReadStart
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Kafka 消息读写的实连基准测试（无 UI，验证 [KafkaMessageService] 的读 / 发 / 续读
 * 闭环）—— 与 [com.mqclient.ibmmq.diag.IbmMQSslDiagTest] 同一套「-D 开关、默认跳过」
 * 模式，避免日常 jvmTest 误触网络。
 *
 * 运行方式（所有参数可用系统属性覆盖）：
 *
 * ```
 * /opt/software/build/gradle :kafka-service:jvmTest \
 *   --tests "com.mqclient.kafka.diag.KafkaMessageDiagTest" \
 *   -Dkafkadiag.enabled=true -Dkafkadiag.user=app -Dkafkadiag.pass=... \
 *   -Dkafkadiag.truststore=/path/to/trust.p12 -Dkafkadiag.truststorePassword=...
 * ```
 *
 * 可覆盖的系统属性（`-Dkafkadiag.xxx=...`）：
 *  - kafkadiag.enabled            —— **必须为 true 才会真正连远端**；默认跳过
 *  - kafkadiag.bootstrap          —— 默认 chillbear 三节点（11207-11209）
 *  - kafkadiag.protocol           —— 默认 SASL_SSL；kafkadiag.mechanism 默认 SCRAM-SHA-256
 *  - kafkadiag.user / kafkadiag.pass
 *  - kafkadiag.truststore / kafkadiag.truststorePassword —— 自签 CA 时必给
 *  - kafkadiag.topic              —— 默认 `app-diag`（远端账号仅 app- 前缀 topic 有 ACL）
 *  - kafkadiag.timeout            —— 连接超时秒数（读取 deadline 同源），默认 30
 *
 * 闭环：send 3 条（含 header / binary header / null key）→ 记录 partition/offset →
 * AtOffset 读回断言 → readMessageDetail 断言 → readMoreMessages 续读 → AtTimestamp
 * 回读。全程不 commit offset（browse consumer assign-only）。
 */
class KafkaMessageDiagTest {

    private fun prop(key: String, default: String): String =
        System.getProperty("kafkadiag.$key") ?: default

    private fun config(): KafkaConfig = KafkaConfig(
        bootstrapServers = prop("bootstrap", "www.chillbear.top:11207,www.chillbear.top:11208,www.chillbear.top:11209"),
        securityProtocol = prop("protocol", "SASL_SSL"),
        saslMechanism = prop("mechanism", "SCRAM-SHA-256"),
        username = prop("user", ""),
        password = prop("pass", ""),
        truststorePath = prop("truststore", ""),
        truststorePassword = prop("truststorePassword", ""),
        connectionTimeout = prop("timeout", "30").toInt(),
    )

    @Test
    fun sendThenRead() = runBlocking {
        if (!prop("enabled", "false").toBoolean()) {
            println("KafkaMessageDiagTest skipped — set -Dkafkadiag.enabled=true to run against a real cluster")
            return@runBlocking
        }
        val config = config()
        val topic = prop("topic", "app-diag")
        println("=========== KAFKA MESSAGE DIAG ===========")
        println("  bootstrap        = ${config.bootstrapServers}")
        println("  protocol         = ${config.securityProtocol} / ${config.saslMechanism}")
        println("  user             = ${config.username.ifBlank { "(anonymous)" }}")
        println("  truststore       = ${config.truststorePath.ifBlank { "(none)" }}")
        println("  topic            = $topic")
        println("  timeout          = ${config.connectionTimeout}s")

        val service = KafkaMessageService()
        val startedAt = System.currentTimeMillis()

        // 1. 发送 3 条：普通 / 带 headers（含 binary header）/ null key
        val sent = mutableListOf<Pair<Int, Long>>()   // partition to offset
        val payloads = listOf(
            "diag-msg-1-纯文本" to listOf<KafkaMessageHeader>(),
            "diag-msg-2-json" to listOf(
                KafkaMessageHeader("source", "diag".toByteArray()),
                KafkaMessageHeader("seq-bin", byteArrayOf(0x00, 0x01, 0x02, 0x03)),
            ),
            "diag-msg-3" to emptyList(),
        )
        payloads.forEachIndexed { index, (value, headers) ->
            // index 1/2 共用 key → 同分区，续读断言才成立；index 0 null key（sticky 分区）
            val key = when (index) {
                1, 2 -> "diag-key-1".toByteArray()
                else -> null
            }
            val result = service.sendMessage(config, topic, value.toByteArray(), key, headers = headers)
            assertTrue(result.success, "send #$index failed: ${result.errorMessage}")
            println("  sent #$index -> partition=${result.partition} offset=${result.offset} ts=${result.timestamp}")
            assertTrue(result.partition != null && result.offset != null && (result.timestamp ?: 0) > 0)
            sent += result.partition!! to result.offset!!
        }

        // 2. AtOffset 读回第一条，断言字节与 headers
        val (p0, o0) = sent[0]
        val page = service.readMessages(config, topic, p0, KafkaReadStart.AtOffset(p0, o0), count = 1)
        assertEquals(1, page.messages.size, "AtOffset read returned ${page.messages.size} rows")
        assertEquals(o0, page.messages[0].offset)
        assertEquals(payloads[0].first, page.messages[0].valuePreview)

        val detail = service.readMessageDetail(config, topic, p0, o0)
        assertEquals(payloads[0].first, String(detail.valueBytes, Charsets.UTF_8))
        assertTrue(detail.headers.isEmpty(), "expected no headers on msg #1")

        // 3. 详情断言 headers（第二条：1 个文本 header + 1 个 binary header）
        val (p1, o1) = sent[1]
        val detail2 = service.readMessageDetail(config, topic, p1, o1)
        assertEquals("diag", String(detail2.headers.first { it.key == "source" }.value!!, Charsets.UTF_8))
        assertTrue(detail2.headers.any { it.key == "seq-bin" && it.value.contentEquals(byteArrayOf(0, 1, 2, 3)) })

        // 4. 续读：从第二条的 offset+1 继续，应能读到第三条（同 key → 同分区）
        val more = service.readMoreMessages(config, topic, mapOf(p1 to o1 + 1), count = 10)
        assertTrue(more.messages.any { it.offset == sent[2].second && it.partition == sent[2].first },
            "readMore should contain msg #3 at p${sent[2].first} o${sent[2].second}, got ${more.messages.map { it.partition to it.offset }}")

        // 5. AtTimestamp 回读：以第 1 条的时间戳定位（刚刚发送，必命中）
        val recent = service.readMessages(
            config, topic, sent[0].first,
            KafkaReadStart.AtTimestamp(Instant.now().minusSeconds(60).toEpochMilli()),
            count = 50,
        )
        assertTrue(recent.messages.isNotEmpty(), "AtTimestamp read should find recent messages")

        // 6. Latest 往前读：应包含刚发的 3 条（全分区合并）
        val latest = service.readMessages(config, topic, null, KafkaReadStart.Latest(count = 50), count = 50)
        val latestKeys = latest.messages.map { it.partition to it.offset }.toSet()
        assertTrue(sent.all { it in latestKeys }, "Latest read should contain all sent messages, got $latestKeys")

        println(">>> SEND+READ OK (${sent.size} messages, ${System.currentTimeMillis() - startedAt}ms)")
        println("=========== DIAG END ===========")
    }
}
