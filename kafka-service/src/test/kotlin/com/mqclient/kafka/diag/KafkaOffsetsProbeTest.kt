package com.mqclient.kafka.diag

import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import org.apache.kafka.common.TopicPartition

/**
 * 离线契约测试：`probeOffsets` 把「哪一组读失败」记下来，而不是静默丢掉。
 *
 * 为什么需要它：`KafkaGroupLagBatchLiveTest` 的等价断言是 `assertEquals(serial, batched)`。
 * 只要两个取数函数把失败组丢掉，集群中途掉线时两张空表也「相等」，测试照样绿 ——
 * 这种真空通过只有在真集群上才暴露，而真集群恰恰是最难得的资源。把**收集失败**这层
 * 抽成可注入的纯函数后，机制本身就能在无 broker 的机器上被证明。
 *
 * 三条语义（都是 live 测试赖以成立的前提）：
 *  1. 成功组进 `offsets`，失败组进 `failures`，互不污染；
 *  2. 失败文案取根因（`ExecutionException` 套娃剥掉）；
 *  3. 取消**不**被记成「这一组读失败」—— 否则关窗口时的取消会被伪装成环境问题。
 */
class KafkaOffsetsProbeTest {

    private val tp = TopicPartition("orders", 0)

    @Test
    fun successfulAndFailedGroupsAreSeparated() {
        val probe = probeOffsets(listOf("good", "bad")) { groupId ->
            if (groupId == "bad") throw IllegalStateException("coordinator not available")
            mapOf(tp to 42L)
        }

        assertEquals(setOf("good"), probe.offsets.keys, "成功的组只应出现在 offsets")
        assertEquals(mapOf(tp to 42L), probe.offsets.getValue("good"))
        assertEquals(setOf("bad"), probe.failures.keys, "失败的组必须被记下来，不能被丢掉")
        assertTrue(
            probe.failures.getValue("bad").contains("coordinator not available"),
            "失败原因要能看出是哪一类问题：${probe.failures}",
        )
    }

    @Test
    fun allGroupsFailingDoesNotYieldAnEmptyVacuouslyEqualResult() {
        // 这就是旧实现的漏洞形态：全部失败 → offsets 为空；若 failures 也被丢掉，
        // 「串行版 == 批量版」就会拿两张空表通过。现在失败必须留下痕迹。
        val probe = probeOffsets(listOf("a", "b")) { throw RuntimeException("boom") }

        assertEquals(emptyMap(), probe.offsets)
        assertEquals(setOf("a", "b"), probe.failures.keys, "全失败必须留下全部的失败组")
    }

    @Test
    fun failureMessageUnwrapsToRootCause() {
        val probe = probeOffsets(listOf("g")) {
            throw ExecutionException(IllegalStateException("Invalid SASL_SSL handshake"))
        }

        val message = probe.failures.getValue("g")
        assertEquals("Invalid SASL_SSL handshake", message, "文案应取根因，而不是外层包装")
    }

    @Test
    fun cancellationIsRethrownAndNotRecordedAsFailure() {
        val cancellation = CancellationException("window closed")

        assertFailsWith<CancellationException> {
            probeOffsets(listOf("g")) { throw cancellation }
        }
    }

    @Test
    fun emptyGroupListYieldsEmptyProbe() {
        val probe = probeOffsets(emptyList()) { error("不该被调用") }

        assertEquals(emptyMap(), probe.offsets)
        assertEquals(emptyMap(), probe.failures)
    }
}
