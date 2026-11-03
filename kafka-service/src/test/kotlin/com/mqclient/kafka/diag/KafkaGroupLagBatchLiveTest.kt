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
import kotlinx.coroutines.CancellationException
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
 *     语义；若直接 `.all()` 就会被一个坏组连坐整轮；
 *  4. **不许真空通过**：取位点失败的组被显式收集（`OffsetsProbe.failures`）并断言为空。
 *     早先两个取数函数用 `runCatching{}.getOrNull()` 静默丢失败组，一旦集群在测试
 *     中途掉线，串行与批量都会返回空表 —— 「两边等价」于是拿两个空 Map 通过。
 *     失败可见之后，「真实组一个都不许读失败」才是能被证伪的断言。
 *
 * 跳过条件（环境问题 ≠ 代码回归，与 `PcfScanBudgetLiveTest` / `RabbitMqScanPartialLiveTest`
 * 的 `assumeLive` 口径一致）：
 *  - `config.toml` 里没有 Kafka 连接；
 *  - TLS 连接的目标信任库文件不在；
 *  - **集群连不上**：首次 `listConsumerGroups` 失败即判 SKIP。测试机上 11207-11209
 *    是各 MQ 的复用端口，服务器资源只够起一种中间件 —— 端口上可能跑的是 RabbitMQ 的
 *    管理 API 或别的服务，此时握手必然失败。旧版只看「配置在不在」就直连，于是这条
 *    被报成 FAILED，把环境问题伪装成代码回归。
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
            // 探活：连不上就 SKIP。放在这里而不是配置层，是因为「配置齐全」不代表
            // 「端口上是 Kafka」—— 端口被别的 MQ 占用时，配置看起来完全正常。
            val listings = try {
                withContext(Dispatchers.IO) {
                    admin.listConsumerGroups(ListConsumerGroupsOptions().timeoutMs(timeoutMs.toInt()))
                        .all()
                        .get(timeoutMs, TimeUnit.MILLISECONDS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                assumeLive(
                    "LIVE SKIP: 连不上 Kafka 集群（服务器只起了一种 MQ / 端口被占用？）：${error.brieflyOf()}",
                    false,
                )
                null
            }
            val groupIds = listings.orEmpty().map { it.groupId() }.sorted()
            println("  集团数 = ${groupIds.size}: $groupIds")
            // 在 try 里：assumption 异常会穿出 finally 正常关 admin 后再被 JUnit 记为 skipped。
            assumeLive("LIVE SKIP: 集群上没有任何消费组，无法比较两种取法", groupIds.isNotEmpty())

            val serialStart = System.currentTimeMillis()
            val serial = serialOffsets(admin, groupIds, timeoutMs)
            val serialMs = System.currentTimeMillis() - serialStart

            val batchStart = System.currentTimeMillis()
            val batched = batchedOffsets(admin, groupIds, timeoutMs)
            val batchMs = System.currentTimeMillis() - batchStart

            println("  逐组串行 = ${serial.offsets.size}/${groupIds.size} 组（失败 ${serial.failures.size}）/ ${serialMs}ms")
            println("  一次批量 = ${batched.offsets.size}/${groupIds.size} 组（失败 ${batched.failures.size}）/ ${batchMs}ms")
            // 先查失败再比等价：全失败时两张空表「相等」，等价断言单独存在就会真空通过。
            assertEquals(emptyMap(), serial.failures, "串行取位点有失败组，等价结论不成立")
            assertEquals(emptyMap(), batched.failures, "批量取位点有失败组，等价结论不成立")
            assertEquals(groupIds.toSet(), batched.offsets.keys, "批量版必须覆盖全部消费组")
            assertEquals(serial.offsets, batched.offsets, "批量版与串行版必须给出完全相同的位点")
            assertTrue(
                batchMs <= serialMs * 1.5 + 50,
                "批量版不该比串行慢：batch=${batchMs}ms serial=${serialMs}ms",
            )

            // 单组隔离：批量请求里混一个不存在的组。
            val bogus = "mqclient-no-such-group-" + UUID.randomUUID()
            val mixed = batchedOffsets(admin, groupIds + bogus, timeoutMs)
            println("  混入坏组后的返回组数 = ${mixed.offsets.size}（期望 ${groupIds.size}）")
            assertEquals(groupIds.toSet(), mixed.offsets.keys, "坏组只丢它自己，真实组必须全部返回")
            assertTrue(
                mixed.failures.keys.none { it in groupIds },
                "坏组不得连坐真实组：${mixed.failures}",
            )
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
    ): OffsetsProbe = probeOffsets(groupIds) { groupId ->
        admin.listConsumerGroupOffsets(groupId)
            .partitionsToOffsetAndMetadata()
            .get(timeoutMs, TimeUnit.MILLISECONDS)
            .mapValues { (_, m) -> m.offset() }
    }

    /** 新实现：一次批量请求 + 按组 future 取结果。 */
    private fun batchedOffsets(
        admin: AdminClient,
        groupIds: List<String>,
        timeoutMs: Long,
    ): OffsetsProbe {
        val offsets = admin.listConsumerGroupOffsets(
            groupIds.associateWith { ListConsumerGroupOffsetsSpec() },
            ListConsumerGroupOffsetsOptions().timeoutMs(timeoutMs.toInt()),
        )
        return probeOffsets(groupIds) { groupId ->
            offsets.partitionsToOffsetAndMetadata(groupId)
                .get(timeoutMs, TimeUnit.MILLISECONDS)
                .mapValues { (_, m) -> m.offset() }
        }
    }
}

/** 解包到根因再取一行文案：`ExecutionException: ...Caused by: KafkaException...` 直接打太长。 */
internal fun Throwable.brieflyOf(): String {
    val root = generateSequence(this) { it.cause }.last()
    return root.message ?: root::class.simpleName ?: "unknown"
}

/**
 * 一次取位点的结果：按组的成功位点 + 按组的失败原因。
 *
 * 失败必须**带出来**而不是静默丢掉 —— 丢掉之后「批量与串行等价」在两边全军覆没
 * 时反而更容易通过（两张空表相等），这正是这条测试上一版的真空口子。
 */
internal data class OffsetsProbe(
    val offsets: Map<String, Map<TopicPartition, Long>>,
    val failures: Map<String, String>,
)

/** 逐组读、逐组记成败；取消照常上抛，不被当成「这一组读失败」。 */
internal fun probeOffsets(
    groupIds: List<String>,
    read: (String) -> Map<TopicPartition, Long>,
): OffsetsProbe {
    val offsets = LinkedHashMap<String, Map<TopicPartition, Long>>()
    val failures = LinkedHashMap<String, String>()
    groupIds.forEach { groupId ->
        try {
            offsets[groupId] = read(groupId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failures[groupId] = error.brieflyOf()
        }
    }
    return OffsetsProbe(offsets, failures)
}
