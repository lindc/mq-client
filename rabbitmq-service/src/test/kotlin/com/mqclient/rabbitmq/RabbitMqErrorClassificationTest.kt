package com.mqclient.rabbitmq

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ShutdownSignalException
import java.io.IOException
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 钉死 AMQP 错误分类的**结构化**契约：以 [ShutdownSignalException.reason] 携带的
 * AMQP Method（`Channel.Close` / `Connection.Close` 的 replyCode）裁决，**绝不**
 * 做消息文本匹配（broker 升级 / 本地化会让文本判断悄悄失效）。
 */
class RabbitMqErrorClassificationTest {

    private fun channelClose(replyCode: Int): ShutdownSignalException =
        ShutdownSignalException(
            false,
            false,
            AMQP.Channel.Close.Builder()
                .replyCode(replyCode)
                .replyText("err")
                .classId(60)
                .methodId(40)
                .build(),
            "test",
        )

    private fun connectionClose(replyCode: Int = 320): ShutdownSignalException =
        ShutdownSignalException(
            true,
            false,
            AMQP.Connection.Close.Builder()
                .replyCode(replyCode)
                .replyText("err")
                .classId(0)
                .methodId(0)
                .build(),
            "test",
        )

    // ---- isStreamUnsupported：只有 Channel.Close(540) ----

    @Test
    fun `channel close 540 is stream unsupported`() {
        assertTrue(isStreamUnsupported(channelClose(540)))
    }

    @Test
    fun `wrapped channel close 540 is stream unsupported`() {
        assertTrue(isStreamUnsupported(IOException("wrapped", channelClose(540))))
    }

    @Test
    fun `channel close 404 is not stream unsupported`() {
        assertFalse(isStreamUnsupported(channelClose(404)))
    }

    @Test
    fun `plain text mentioning NOT_IMPLEMENTED does not classify (no string matching)`() {
        assertFalse(
            isStreamUnsupported(IllegalStateException("NOT_IMPLEMENTED - basic.get not supported")),
        )
    }

    // ---- isAmqpConnectionFailure：Connection.Close / 传输层 = true；Channel.Close = false ----

    @Test
    fun `connection close is connection failure`() {
        assertTrue(isAmqpConnectionFailure(connectionClose()))
    }

    @Test
    fun `io exception wrapping connection close is connection failure`() {
        assertTrue(isAmqpConnectionFailure(IOException("broken pipe", connectionClose())))
    }

    @Test
    fun `socket exception is connection failure`() {
        assertTrue(isAmqpConnectionFailure(SocketException("Connection reset")))
    }

    @Test
    fun `channel close 404 is not connection failure (queue missing)`() {
        assertFalse(isAmqpConnectionFailure(IOException("wrapped", channelClose(404))))
    }

    @Test
    fun `already-closed with channel close reason is not connection failure`() {
        // 本地 discardChannel 过的 channel 上的后续操作：channel 级，不动共享连接。
        assertFalse(isAmqpConnectionFailure(channelClose(404)))
    }

    @Test
    fun `unreasoned shutdown signal is connection failure`() {
        assertTrue(
            isAmqpConnectionFailure(
                ShutdownSignalException(true, false, null, "test"),
            ),
        )
    }

    @Test
    fun `bare io exception is connection failure (transport fallback)`() {
        assertTrue(isAmqpConnectionFailure(IOException("An existing connection was forcibly closed")))
    }

    @Test
    fun `plain text mentioning connection does not classify (no string matching)`() {
        assertFalse(
            isAmqpConnectionFailure(IllegalStateException("connection refused blah")),
        )
    }
}
