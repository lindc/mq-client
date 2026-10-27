package com.mqclient.kafka.diag

import com.mqclient.core.ConnectionStore
import com.mqclient.kafka.KafkaConfig
import com.mqclient.kafka.kafkaConfigFromOptions
import com.mqclient.kafka.toAdminProperties
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions
import org.apache.kafka.common.TopicPartition
import com.mqclient.testing.assumeLive
import com.mqclient.testing.assumeNotNull

/**
 * 真机验证：`KafkaScanService.scanAllGroupLags` 的**批量**取位点真的成立，且不比逐组
 * 串行慢 —— 这是把 N 次 RTT 压成「每个 coordinator 一次」的依据。
 *
 * 盯三件事：
 *  1. **等价**：批量结果与逐组串行结果逐组逐分区完全一致（换实现不能换数据）；
 *  2. **更快**：批量耗时明显低于串行（组数越少差距越小，故只断言「不慢于 1.5 倍
 *     串行」，避免抖动误报）；
 *  3. **单组隔离**：混入一个不存在的组，真实组的位点照常返回，坏组只丢它自己 ——
 *     批量版用 `partitionsToOffsetAndMetadata(groupId)` 的按组 future 才保得住这条
 *     语义；若直接 `.all()` 就会被一个坏组连坐整轮。
 *
 * 没有 Kafka 连接时跳过（`config.toml` 里读不到就认为本机没配）。TLS 连接的目标
 * 信任库丢失时同样跳过 —— 那是环境问题，不该报成代码回归。
 */
class KafkaGroupLagBatchLiveTest {

    @Test
    fun liveBatchedGroupOffsetsMatchSerial() = runBlocking {
        // 无环境 = SKIP（assumption），不是 PASSED —— 见 test-support 的
        // LiveTestAssumptions：旧写法 return 之后 JUnit 记的是「通过」，CI 永远绿。
        val info = assumeNotNull(
            "LIVE SKIP: config.toml 里没有 Kafka 连接",
            ConnectionStore.connections.value.firstOrNull { it.serviceId == "kafka" },
        )
        val config = kafkaConfigFromOptions(info.options)
        assumeLive(
            "LIVE SKIP: 信任库不存在，本机连不上该集群：${config.truststorePath}",
            config.truststoreAvailable(),
        )
        val timeoutMs = config.connectionTimeout * 1_000L
        println("=========== KAFKA GROUP LAG BATCH DIAG ===========")
        println("  bootstrap = ${config.bootstrapServers}")
        println("  timeout   = ${config.connectionTimeout}s")

        val admin = withContext(Dispatchers.IO) { AdminClient.create(config.toAdminProperties()) }
        try {
            val groupIds = withContext(Dispatchers.IO) {
                admin.listConsumerGroups(ListConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()))
                    .all()
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
                    .map { it.groupId() }
                    .sorted()
            }
            println("  集团数 = ${groupIds.size}: $groupIds")
            // 在 try 里：assumption 异常会穿出 finally 正常关 admin 后再被 JUnit 记为 skipped。
            assumeLive("LIVE SKIP: 集群上没有任何消费组，无法比较两种取法", groupIds.isNotEmpty())

            val serialStart = System.currentTimeMillis()
            val serial = serialOffsets(admin, groupIds, timeoutMs)
            val serialMs = System.currentTimeMillis() - serialStart

            val batchStart = System.currentTimeMillis()
            val batched = batchedOffsets(admin, groupIds, timeoutMs)
            val batchMs = System.currentTimeMillis() - batchStart

            println("  逐组串行 = ${serial.size} 组 / ${serialMs}ms")
            println("  一次批量 = ${batched.size} 组 / ${batchMs}ms")
            assertEquals(serial, batched, "批量版与串行版必须给出完全相同的位点")
            assertTrue(
                batchMs <= serialMs * 1.5 + 50,
                "批量版不该比串行慢：batch=${batchMs}ms serial=${serialMs}ms",
            )

            // 单组隔离：批量请求里混一个不存在的组。
            val bogus = "mqclient-no-such-group-" + UUID.randomUUID()
            val mixed = batchedOffsets(admin, groupIds + bogus, timeoutMs)
            println("  混入坏组后的返回组数 = ${mixed.size}（期望 ${groupIds.size}）")
            assertEquals(groupIds.toSet(), mixed.keys, "坏组只丢它自己，真实组必须全部返回")
            println(">>> BATCH OK: 等价 + 不慢（${serialMs}ms → ${batchMs}ms）+ 单组隔离")
            println("=========== DIAG DONE ===========")
        } finally {
            withContext(Dispatchers.IO) { admin.close(Duration.ZERO) }
        }
    }

    /** TLS 且指定了 truststore 时，文件必须在 —— 不在就是环境缺件，不是代码回归。 */
    private fun KafkaConfig.truststoreAvailable(): Boolean {
        val path = truststorePath.trim()
        if (path.isEmpty()) return true
        return File(path).isFile
    }

    /** 旧实现：每组一次 listConsumerGroupOffsets，串行 `.get()`。 */
    private fun serialOffsets(
        admin: AdminClient,
        groupIds: List<String>,
        timeoutMs: Long,
    ): Map<String, Map<TopicPartition, Long>> = groupIds.mapNotNull { groupId ->
        runCatching {
            groupId to admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata()
                .get(timeoutMs, TimeUnit.MILLISECONDS)
                .mapValues { (_, m) -> m.offset() }
        }.getOrNull()
    }.toMap()

    /** 新实现：一次批量请求 + 按组 future 取结果。 */
    private fun batchedOffsets(
        admin: AdminClient,
        groupIds: List<String>,
        timeoutMs: Long,
    ): Map<String, Map<TopicPartition, Long>> {
        val offsets = admin.listConsumerGroupOffsets(
            groupIds.associateWith { ListConsumerGroupOffsetsSpec() },
            ListConsumerGroupOffsetsOptions().timeoutMs(timeoutMs.toInt()),
        )
        return groupIds.mapNotNull { groupId ->
            runCatching {
                groupId to offsets.partitionsToOffsetAndMetadata(groupId)
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
                    .mapValues { (_, m) -> m.offset() }
            }.getOrNull()
        }.toMap()
    }
}
