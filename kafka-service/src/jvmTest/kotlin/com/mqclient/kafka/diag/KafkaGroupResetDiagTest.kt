package com.mqclient.kafka.diag

import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import com.mqclient.kafka.KafkaConfig
import com.mqclient.kafka.KafkaGroupResetTo
import com.mqclient.kafka.KafkaScanService
import com.mqclient.kafka.toAdminProperties
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import com.mqclient.testing.assumeLive

/**
 * 消费组「按时间点重置位移」的实连基准测试（无 UI，验证 [KafkaScanService.resetGroupOffsets]
 * 的状态守卫 / AtTimestamp / -1 兜底闭环）—— 与 [KafkaMessageDiagTest] 同一套
 * 「-D 开关、默认跳过」模式，避免日常 jvmTest 误触网络。
 *
 * 运行方式（与 KafkaMessageDiagTest 同参数族）：
 *
 * ```
 * /opt/software/build/gradle :kafka-service:jvmTest \
 *   --tests "com.mqclient.kafka.diag.KafkaGroupResetDiagTest" \
 *   -Dkafkadiag.enabled=true -Dkafkadiag.user=app -Dkafkadiag.pass=... \
 *   -Dkafkadiag.mechanism=PLAIN \
 *   -Dkafkadiag.truststore=.local-test/client-truststore.p12 \
 *   -Dkafkadiag.truststorePassword=...
 * ```
 *
 * 闭环：发 3 条**显式 CreateTime**（T-120s / T-60s / T，固定分区 0）→ 临时消费者
 * commit earliest 建 EMPTY 组 → **负例**：起一个活跃消费者（组转 Stable），断言重置被
 * 状态守卫拒绝 → 收工回 EMPTY → AtTimestamp(T-60s) 重置断言位点 = 第 2 条的 offset →
 * 未来时间戳（全 -1）断言兜底到 latest → Earliest 档回归断言。组用时间戳后缀隔离，
 * 收尾删除（ACL 不足时跳过并打印）。
 */
class KafkaGroupResetDiagTest {

    private fun prop(key: String, default: String): String =
        System.getProperty("kafkadiag.$key") ?: default

    private fun config(): KafkaConfig = KafkaConfig(
        bootstrapServers = prop("bootstrap", "www.chillbear.top:11207,www.chillbear.top:11208,www.chillbear.top:11209"),
        securityProtocol = prop("protocol", "SASL_SSL"),
        saslMechanism = prop("mechanism", "PLAIN"),
        username = prop("user", ""),
        password = prop("pass", ""),
        truststorePath = prop("truststore", ""),
        truststorePassword = prop("truststorePassword", ""),
        connectionTimeout = prop("timeout", "30").toInt(),
    )

    /** 最小化模块上下文（resetGroupOffsets 不碰 emit，构造能过即可）。 */
    private fun testContext(): ServiceContext {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val eventBus = EventBus(scope)
        return ServiceContext.of("kafka", eventBus, scope, JobScheduler(scope, eventBus))
    }

    private fun clientProps(groupId: String?) = java.util.Properties().apply {
        putAll(config().toAdminProperties())
        if (groupId != null) put("group.id", groupId)
        put("enable.auto.commit", false)
        put("key.deserializer", ByteArrayDeserializer::class.java.name)
        put("value.deserializer", ByteArrayDeserializer::class.java.name)
    }

    @Test
    fun resetByTimestamp() = runBlocking {
        // 默认不连远端 = SKIP，不是 PASSED。
        assumeLive(
            "KafkaGroupResetDiagTest skipped — set -Dkafkadiag.enabled=true to run against a real cluster",
            prop("enabled", "false").toBoolean(),
        )
        val config = config()
        val timeoutMs = config.connectionTimeout * 1_000L
        val topic = prop("topic", "app-diag-reset")
        val group = prop("group", "app-diag-reset-" + System.currentTimeMillis())
        println("=========== KAFKA GROUP RESET DIAG ===========")
        println("  bootstrap = ${config.bootstrapServers}")
        println("  protocol  = ${config.securityProtocol} / ${config.saslMechanism}")
        println("  topic     = $topic")
        println("  group     = $group")

        val scanService = KafkaScanService(testContext())
        val admin = withContext(Dispatchers.IO) { AdminClient.create(config.toAdminProperties()) }
        try {
            // ---- 1. 确保 topic 存在（单分区足够；不存在则建）----
            try {
                admin.describeTopics(setOf(topic)).allTopicNames().get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                if (e.cause !is UnknownTopicOrPartitionException) throw e
                admin.createTopics(listOf(NewTopic(topic, 1, 1))).all().get(timeoutMs, TimeUnit.MILLISECONDS)
                println("  topic created (1 partition, rf=1)")
            }
            val tp = TopicPartition(topic, 0)

            // ---- 2. 发 3 条显式 CreateTime 的消息（固定分区 0，时间错开 60s）----
            val producerProps = java.util.Properties().apply {
                putAll(config.toAdminProperties())
                put("key.serializer", ByteArraySerializer::class.java.name)
                put("value.serializer", ByteArraySerializer::class.java.name)
            }
            val now = System.currentTimeMillis()
            val sent = mutableListOf<Pair<Long, Long>>() // offset to timestamp
            KafkaProducer<ByteArray, ByteArray>(producerProps).use { producer ->
                listOf(now - 120_000L, now - 60_000L, now).forEach { ts ->
                    val meta = producer
                        .send(ProducerRecord<ByteArray, ByteArray>(topic, 0, ts, null, "reset-diag-$ts".toByteArray()))
                        .get()
                    assertEquals(ts, meta.timestamp(), "broker should keep CreateTime for ts=$ts")
                    sent += meta.offset() to meta.timestamp()
                }
            }
            println("  sent (partition 0): ${sent.mapIndexed { i, (o, ts) -> "#$i offset=$o ts=$ts" }}")

            // ---- 3. 临时消费者 commit earliest 建组 → EMPTY + 有已提交位移 ----
            KafkaConsumer<ByteArray, ByteArray>(clientProps(group)).use { consumer ->
                consumer.assign(listOf(tp))
                val beginning = consumer.beginningOffsets(listOf(tp)).getValue(tp)
                consumer.commitSync(mapOf(tp to OffsetAndMetadata(beginning)))
                println("  group seeded: committed p0=$beginning (earliest)")
            }
            awaitGroupState(admin, group, "EMPTY", timeoutMs)

            // ---- 4. 负例：活跃消费者（组 Stable）→ 状态守卫拒绝重置 ----
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val member = Thread {
                KafkaConsumer<ByteArray, ByteArray>(clientProps(group)).use { consumer ->
                    consumer.subscribe(listOf(topic))
                    while (!stop.get()) consumer.poll(Duration.ofMillis(500))
                }
            }
            member.start()
            try {
                awaitGroupState(admin, group, "STABLE", timeoutMs)
                val failure = runCatching {
                    scanService.resetGroupOffsets(admin, group, KafkaGroupResetTo.Latest, timeoutMs)
                }.exceptionOrNull()
                assertTrue(
                    failure is IllegalStateException && failure.message?.contains("活跃成员") == true,
                    "state guard should reject reset with active members, got: $failure",
                )
                println("  guard OK: reset rejected while group STABLE (${failure?.message})")
            } finally {
                stop.set(true)
                member.join()
            }
            awaitGroupState(admin, group, "EMPTY", timeoutMs)

            // ---- 5. AtTimestamp：cutoff = 第 2 条的 ts → p0 重置到第 2 条的 offset ----
            val count = scanService.resetGroupOffsets(
                admin, group, KafkaGroupResetTo.AtTimestamp(sent[1].second), timeoutMs,
            )
            assertEquals(1, count, "only p0 has committed offsets")
            assertCommitted(admin, group, tp, sent[1].first, timeoutMs, "AtTimestamp -> 2nd record")
            println("  AtTimestamp OK: p0 -> offset ${sent[1].first} ($count partition)")

            // ---- 6. 未来时间戳（全 -1）→ 兜底 latest ----
            val end = admin.listOffsets(mapOf(tp to OffsetSpec.latest()))
                .all().get(timeoutMs, TimeUnit.MILLISECONDS).getValue(tp).offset()
            scanService.resetGroupOffsets(
                admin, group, KafkaGroupResetTo.AtTimestamp(now + 3_600_000L), timeoutMs,
            )
            assertCommitted(admin, group, tp, end, timeoutMs, "future ts fallback -> latest")
            println("  -1 fallback OK: p0 -> latest=$end")

            // ---- 7. Earliest 档回归 ----
            val beginning = admin.listOffsets(mapOf(tp to OffsetSpec.earliest()))
                .all().get(timeoutMs, TimeUnit.MILLISECONDS).getValue(tp).offset()
            scanService.resetGroupOffsets(admin, group, KafkaGroupResetTo.Earliest, timeoutMs)
            assertCommitted(admin, group, tp, beginning, timeoutMs, "Earliest")
            println("  Earliest OK: p0 -> earliest=$beginning")

            println(">>> GROUP RESET DIAG OK (${System.currentTimeMillis() - now}ms)")
        } finally {
            // 收尾删组（需要 group 的 DELETE ACL，缺了只打印不失败）。
            runCatching {
                admin.deleteConsumerGroups(setOf(group)).all().get(timeoutMs, TimeUnit.MILLISECONDS)
                println("  group $group deleted")
            }.onFailure { println("  group cleanup skipped: ${it.message}") }
            runCatching { admin.close(Duration.ofSeconds(5)) }
        }
        println("=========== DIAG END ===========")
    }

    /** 轮询 describe 直到组状态等于 [expected]（忽略大小写）或超时。 */
    private fun awaitGroupState(admin: AdminClient, group: String, expected: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = admin.describeConsumerGroups(listOf(group))
                .describedGroups()[group]
                ?.get(timeoutMs, TimeUnit.MILLISECONDS)
                ?.state()?.toString()?.uppercase()
            if (state == expected.uppercase()) {
                println("  group $group state = $state")
                return
            }
            Thread.sleep(500)
        }
        throw AssertionError("group $group did not reach $expected within ${timeoutMs}ms")
    }

    /** 断言组的已提交位移：p0 committed == [expected]。 */
    private fun assertCommitted(
        admin: AdminClient,
        group: String,
        tp: TopicPartition,
        expected: Long,
        timeoutMs: Long,
        label: String,
    ) {
        val committed = admin.listConsumerGroupOffsets(group)
            .partitionsToOffsetAndMetadata()
            .get(timeoutMs, TimeUnit.MILLISECONDS)[tp]?.offset()
        assertEquals(expected, committed, "$label: committed($tp)=$committed expected=$expected")
    }
}
