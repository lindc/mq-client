package com.mqclient.rabbitmq

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ChannelContinuationTimeoutException
import com.rabbitmq.client.ShutdownSignalException
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import javax.net.ssl.SSLException

/** AMQP reply code：basic.get 在 Stream 队列上被 broker 以 540 NOT_IMPLEMENTED 拒绝。 */
internal const val AMQP_REPLY_NOT_IMPLEMENTED = 540

/**
 * Stream 队列的 basic.get 被 broker 以 **540 NOT_IMPLEMENTED** 拒绝（含 cause 链）。
 *
 * 结构化判断：amqp-client 把「broker 关闭 channel」抛成 [ShutdownSignalException]，
 * 其 [ShutdownSignalException.reason] 携带 AMQP Method —— `Channel.Close(replyCode=540)`
 * 即 Stream 队列不支持 basic.get。**不做消息文本匹配**：broker 升级 / 本地化都会让
 * `contains("NOT_IMPLEMENTED")` 之类的判断悄悄失效。
 */
internal fun isStreamUnsupported(error: Throwable): Boolean {
    var current: Throwable? = error
    while (current != null) {
        if (current is ShutdownSignalException) {
            val reason = current.reason
            if (reason is AMQP.Channel.Close && reason.replyCode == AMQP_REPLY_NOT_IMPLEMENTED) {
                return true
            }
        }
        current = current.cause
    }
    return false
}

/**
 * 区分「连接级故障」与 404 / 540 等 **channel 级业务错误**：只有前者才值得作废共享
 * Connection（[RabbitMqConnectionManager.invalidateAmqpConnection]）。
 *
 * 结构化判断（同样不做消息文本匹配），按 cause 链**最近的信号**裁决：
 *  - [ShutdownSignalException] + `AMQP.Connection.Close` → broker/传输层关了连接 → **true**
 *  - [ShutdownSignalException] + `AMQP.Channel.Close`（404 队列不存在 / 540 不支持 /
 *    406 预条件失败）→ channel 级，连接还活着 → **false**
 *  - 传输层异常家族（[SocketException] / [SSLException] / [EOFException]）→ **true**
 *  - [ChannelContinuationTimeoutException] → RPC 超时，连接健康 → **false**
 *  - 裸 [IOException]（Windows 的 "forcibly closed" 等，无可辨认 reason）→ **true** 兜底
 *
 * [AlreadyClosedException] 是 [ShutdownSignalException] 的子类：其 reason 是
 * `Channel.Close` 时同样按 channel 级（false）—— 本地 discardChannel 过的 channel
 * 上的后续操作不会误伤共享连接。
 */
internal fun isAmqpConnectionFailure(error: Throwable): Boolean {
    var current: Throwable? = error
    var sawBareIoException = false
    while (current != null) {
        when (current) {
            is ChannelContinuationTimeoutException -> return false
            is ShutdownSignalException -> when (val reason = current.reason) {
                is AMQP.Connection.Close -> return true
                is AMQP.Channel.Close -> return false
                // broker 未带方法就断开（socket 层死亡 / 连接级 abort）→ 连接级。
                else -> return true
            }
            is SocketException, is SSLException, is EOFException -> return true
            is IOException -> sawBareIoException = true
        }
        current = current.cause
    }
    return sawBareIoException
}
