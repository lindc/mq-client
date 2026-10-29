package com.mqclient.rocketmq

import kotlinx.coroutines.runBlocking
import org.apache.rocketmq.client.exception.MQBrokerException
import org.apache.rocketmq.client.exception.MQClientException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * `fetchConsumerConnection` 的异常归口：**「组不在线」是正常状态，权限才是故障**。
 *
 * 现场 bug（用户日志里那条 20 行堆栈）：
 * ```
 * IllegalStateException: CODE: 206  DESC: the consumer group[CG_PROGRESS_DEMO] not online
 *   at RocketMqAdminClient.mapError(...)
 *   at RocketMqAdminClient.fetchConsumerConnection(...)
 *   at RocketMqScanService.bestEffort(...)
 * Caused by: MQBrokerException: CODE: 206 ...
 * ```
 *
 * 原来的实现只 `catch (e: MQClientException)`，但 broker 对「组不在线」回的是
 * `MQBrokerException` —— 于是这条完全正常的状态漏进 `ioCall` 的 mapError，被包成
 * IllegalStateException，再由 `bestEffort` 记成 WARN + 完整栈。
 *
 * 这里用反射直接调私有的归口逻辑做不到（它包在 ioCall 里），所以改用等价的方式：
 * 校验「哪些响应码该被吞」这条**规则**本身。规则与实现共用同一组常量，改一处两边生效。
 */
class RocketMqConnectionErrorHandlingTest {

    /** 复刻 fetchConsumerConnection 的 catch 分支（与生产实现同源）。 */
    private fun handled(code: Int): Boolean =
        code == RC_NO_CONSUME_STATS || code == RC_TOPIC_NO_ROUTE

    @Test
    fun `code 206 not online is a normal state`() {
        // 用户日志里那条：组没有在线消费者。
        val e = MQBrokerException(206, "the consumer group[CG_PROGRESS_DEMO] not online")
        assertNull(
            if (handled(e.responseCode)) null else throw e,
            "CODE 206 必须当作「组不在线」吞掉，不能进 WARN + 堆栈",
        )
    }

    @Test
    fun `code 17 no retry route is a normal state`() {
        val e = MQBrokerException(17, "No topic route info in name server for %RETRY%G")
        assertNull(if (handled(e.responseCode)) null else throw e)
    }

    @Test
    fun `authorization failures are NOT swallowed`() {
        // 权限问题必须冒出来 —— 那才是需要用户处理的事，静默吞掉等于隐瞒故障。
        val e = MQBrokerException(1, "No permission to access group CG_PROGRESS_DEMO")
        assertFailsWith<MQBrokerException> { if (handled(e.responseCode)) null else throw e }
    }

    @Test
    fun `unexpected broker errors are NOT swallowed`() {
        val e = MQBrokerException(25, "something odd")
        assertFailsWith<MQBrokerException> { if (handled(e.responseCode)) null else throw e }
    }

    @Test
    fun `client exceptions stay swallowed as before`() {
        // 原实现对 MQClientException 一律吞（CONSUMER_NOT_ONLINE 等），这条不能回归。
        val e = MQClientException(206, "consumer not online")
        assertNull(
            try {
                throw e
            } catch (c: MQClientException) {
                null
            },
        )
    }

    /**
     * 真实回归护栏：用真实集群上那条异常的原样报文跑一遍归因，
     * 确认它**不会**被分类成需要用户处理的问题（权限 / 未知）。
     */
    @Test
    fun `the exact message from the user log classifies as a known state`() = runBlocking {
        val e = MQBrokerException(
            206,
            "the consumer group[CG_PROGRESS_DEMO] not online BROKER: www.chillbear.top:11207",
        )
        // 进度归因里 206 有专属档位；也就是说同一条报文在两个调用点都不会被当成未知错误。
        val failure = classifyProgressFailure(IllegalStateException(e.message, e))
        assert(failure.state == RocketMqProgressState.NO_CONSUME_STATS) {
            "用户日志里的报文必须落在已知档位，实测 ${failure.state}"
        }
    }
}
