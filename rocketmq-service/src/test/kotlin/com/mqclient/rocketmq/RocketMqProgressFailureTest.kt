package com.mqclient.rocketmq

import org.apache.rocketmq.client.exception.MQBrokerException
import org.apache.rocketmq.client.exception.MQClientException
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 消费进度失败的归因（[classifyProgressFailure]）。
 *
 * 用例里的报文**逐字取自真实集群**（chillbear，RocketMQ 5.3.2），不是编的：
 *
 * ```
 * mqadmin consumerProgress -g CG_Normal
 *   MQClientException: CODE: 17  DESC: No topic route info in name server for the
 *   topic: %RETRY%CG_Normal
 *
 * mqadmin consumerProgress -g chillbear-test-group
 *   MQClientException: CODE: 206 DESC: Not found the consumer group consume stats,
 *   because return offset table is empty, maybe the consumer not online
 * ```
 *
 * 回归重点：**响应码藏在包装异常的 cause 链里**。`RocketMqAdminClient.ioCall` 把协议
 * 异常统一包成 `IllegalStateException`，原来的实现直接在包装上判 `e is MQClientException`
 * —— 永远为 false，于是 CODE 17 的专属文案是死代码，所有失败都退化成通用「查询失败」。
 */
class RocketMqProgressFailureTest {

    /** 复刻 ioCall/mapError 的包装形态：IllegalStateException(cause = 协议异常)。 */
    private fun wrapped(protocolError: Throwable): Throwable =
        IllegalStateException(protocolError.message, protocolError)

    @Test
    fun `code 17 maps to retry topic no route even through the wrapper`() {
        val raw = MQClientException(
            17,
            "No topic route info in name server for the topic: %RETRY%CG_Normal",
        )
        val failure = classifyProgressFailure(wrapped(raw))
        assertEquals(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE, failure.state)
        assertEquals(17, failure.code)
        // 17 有专属说明，不需要把原始码甩给用户
        assertNull(failure.detail)
    }

    @Test
    fun `code 206 maps to no consume stats`() {
        val raw = MQClientException(
            206,
            "Not found the consumer group consume stats, because return offset table is empty, " +
                "maybe the consumer not online",
        )
        val failure = classifyProgressFailure(wrapped(raw))
        assertEquals(RocketMqProgressState.NO_CONSUME_STATS, failure.state)
        assertEquals(206, failure.code)
        assertNull(failure.detail)
    }

    @Test
    fun `broker exception with code 17 is recognized too`() {
        // broker 侧 TOPIC_NOT_EXIST 会以 MQBrokerException 形态回来（KDoc 里记过这条）。
        val raw = MQBrokerException(17, "No topic route info")
        assertEquals(
            RocketMqProgressState.RETRY_TOPIC_NO_ROUTE,
            classifyProgressFailure(wrapped(raw)).state,
        )
    }

    @Test
    fun `acl denial maps to no permission`() {
        // ACL 拒绝没有专属 responseCode（通常是 SYSTEM_ERROR(1) + 报文），只能靠措辞。
        val raw = MQBrokerException(1, "No permission to access group CG_Normal")
        val failure = classifyProgressFailure(wrapped(raw))
        assertEquals(RocketMqProgressState.NO_PERMISSION, failure.state)
    }

    @Test
    fun `acl message in plain IllegalStateException is recognized`() {
        // mapError 已经转过中文 ACL 文案时，链上是 IllegalStateException("ACL 认证失败：…")
        val error = IllegalStateException("ACL 认证失败：检查 AccessKey / SecretKey")
        assertEquals(RocketMqProgressState.NO_PERMISSION, classifyProgressFailure(error).state)
    }

    @Test
    fun `unknown failures keep the raw message for the ui`() {
        val raw = IllegalStateException("something odd happened")
        val failure = classifyProgressFailure(raw)
        assertEquals(RocketMqProgressState.FAILED, failure.state)
        assertEquals("something odd happened", failure.detail)
        assertNull(failure.code)
    }

    @Test
    fun `remoting timeout is a plain failure carrying the detail`() {
        val raw = RemotingTimeoutException("wait response on the channel timeout 3000ms")
        val failure = classifyProgressFailure(wrapped(raw))
        assertEquals(RocketMqProgressState.FAILED, failure.state)
        assertTrue(
            failure.detail!!.contains("timeout"),
            "未知失败必须把原始摘要带给 UI，实测：${failure.detail}",
        )
    }

    @Test
    fun `response code lookup survives a deep cause chain`() {
        val raw = MQClientException(17, "No topic route info")
        val deep = RuntimeException("a", RuntimeException("b", IllegalStateException("c", raw)))
        assertEquals(17, rocketMqResponseCode(deep))
    }

    @Test
    fun `response code lookup terminates on self referential chains`() {
        // 病态 cause 成环不能把排查逻辑挂死（对话框打开即卡住）。
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertNull(rocketMqResponseCode(a))
    }
}
