package com.mqclient.rocketmq

/**
 * 消息域数据模型：浏览/详情一体（[RocketMqMessage]）+ 一页结果（[RocketMqMessagePage]）
 * + 发送结果（[RocketMqSendResult]）。
 *
 * 与 Kafka（Summary / Detail 两件套）的差异：RocketMQ 浏览走 PullConsumer 一次拿到
 * 完整 `MessageExt`（body 全量在手），不存在 Kafka 那种「列表只有预览、详情按
 * (partition, offset) 再现查」的二次定位问题 —— 所以合并成一个模型，浏览表格渲染
 * 预览字段，详情对话框直接用 [RocketMqMessage.body]，无需重新读 broker（浏览到
 * 打开之间消息被过期清除也不会炸）。
 *
 * 刻意不放 [RocketMqSnapshot.kt]：那份文件约定是「扫描缓存快照（Snapshot 后缀）」，
 * 随定时扫描落缓存；消息模型是即时查询的返回值，不进缓存、生命周期只到 UI 渲染为止。
 *
 * ByteArray 字段的 data class equals/hashCode 是引用比较 —— 本类一律不要当
 * LazyColumn key / remember key / 相等判断用（定位消息用 `queueId:offset`）。
 */

/** value 预览的截断字节数（与 Kafka 同值：256 字节 UTF-8 容错解码）。 */
internal const val MESSAGE_PREVIEW_BYTES = 256

/**
 * 一条 RocketMQ 消息（浏览列表行 + 详情共用）：body 全量随行。
 *
 * @param offset 队列内 offset（`MessageExt.queueOffset`）
 * @param msgId broker 侧消息 Id（SendResult.msgId 同口径）
 * @param bornTimestamp 客户端产生时间（epoch millis）
 * @param storeTimestamp broker 落盘时间（epoch millis）
 * @param bornHost 生产者地址（可读文本）
 */
data class RocketMqMessage(
    val topic: String,
    val queueId: Int,
    val offset: Long,
    val msgId: String,
    val tags: String?,
    /** 业务键（`MessageExt.keys`，多值空格分隔），null = 未设置。 */
    val keys: String?,
    val bornTimestamp: Long,
    val storeTimestamp: Long,
    val bornHost: String,
    /** body 前 [MESSAGE_PREVIEW_BYTES] 字节的 UTF-8 容错解码（非法字节出替换符）。 */
    val valuePreview: String,
    /** body 前 [MESSAGE_PREVIEW_BYTES] 字节的 hex 串，供疑似二进制时展示。 */
    val valueHex: String,
    /** 疑似二进制（message-interpreter-api 的 isProbablyBinary），列表据此选 preview 或 hex。 */
    val valueProbablyBinary: Boolean,
    val valueSize: Int,
    /** 完整消息体（详情对话框渲染 / 下载用）。 */
    val body: ByteArray,
    /** 用户属性（发送方 putUserProperty 的内容，系统属性已滤除）。 */
    val userProperties: Map<String, String>,
)

/** 一页读取结果。nextBeginOffset = null 表示已到队列尾（无可续读游标）。 */
data class RocketMqMessagePage(
    val messages: List<RocketMqMessage>,
    /** 队列内下一条的 offset（readMessages 的续读入参）。 */
    val nextBeginOffset: Long?,
)

/** 发送结果（对标 KafkaSendResult）：RocketMQ 多返回 msgId / brokerName。 */
data class RocketMqSendResult(
    val success: Boolean,
    val msgId: String? = null,
    val brokerName: String? = null,
    val queueId: Int? = null,
    val offset: Long? = null,
    val errorMessage: String? = null,
) {
    companion object {
        fun success(msgId: String, brokerName: String, queueId: Int, offset: Long): RocketMqSendResult =
            RocketMqSendResult(
                success = true,
                msgId = msgId,
                brokerName = brokerName,
                queueId = queueId,
                offset = offset,
            )

        fun failure(message: String): RocketMqSendResult = RocketMqSendResult(
            success = false,
            errorMessage = message,
        )
    }
}
