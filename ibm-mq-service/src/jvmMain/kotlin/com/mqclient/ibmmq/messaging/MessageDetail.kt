package com.mqclient.ibmmq.messaging

import com.ibm.mq.MQMessage
import com.ibm.mq.constants.MQConstants

/**
 * 消息详情模型（「详细」对话框用）：[MessageSummary] 的全部字段 + 完整消息体 +
 * 列表阶段不采集的 MQMD 字段。数据由 [getMessageDetail] 按 MessageId 现查
 * （独立 browse 句柄），不经列表缓存。
 *
 * [headers] 是**展示就绪**的有序行：first = i18n key（UI 查表；缺键时原样显示），
 * second = 已组装好的展示值（CodedCharSetId / Encoding 等「原始值 + 可读名」
 * 在 service 侧组装 —— 只有这里摸得到 [MQConstants]）。
 */
data class MessageDetail(
    val messageId: String,
    val correlationId: String,
    val content: ByteArray,
    val dataLength: Int,
    /** 展示就绪的 Header 行（有序）。 */
    val headers: List<Pair<String, String>>,
    /** MQMD.Encoding 原始值。 */
    val encoding: Int,
    /** MQMD.CodedCharSetId 原始值。 */
    val codedCharSetId: Int,
    /** MQMD.MsgType 原始值。 */
    val msgType: Int,
    val putDate: String,
    val putTime: String,
    val priority: Int,
    val persistence: Int,
) {
    /** content 按内容相等比较（ByteArray 引用相等的坑）。 */
    override fun equals(other: Any?): Boolean = other is MessageDetail && other.messageId == messageId
    override fun hashCode(): Int = messageId.hashCode()
}

/** CCSID → 可读字符集名（常用子集；查不到只显示原始值，不猜）。 */
private val CCSID_NAMES: Map<Int, String> = mapOf(
    1208 to "UTF-8",
    1200 to "UCS-2",
    13488 to "UTF-16",
    819 to "ISO-8859-1",
    1252 to "Windows-1252",
    437 to "IBM437",
    850 to "IBM850",
    936 to "GBK",
    1381 to "GBK",
    1386 to "GB18030",
    5488 to "GB18030",
    37 to "EBCDIC US",
    500 to "EBCDIC International",
    1047 to "EBCDIC Open Systems",
)

/** MQMD.CodedCharSetId → 展示值："1208 (UTF-8)"；未知 CCSID 只显示数字。 */
private fun codedCharSetIdDisplay(ccsid: Int): String =
    CCSID_NAMES[ccsid]?.let { "$ccsid ($it)" } ?: ccsid.toString()

/**
 * MQMD.Encoding → 展示值。Encoding 是位掩码（整数 / 十进制 / 浮点各自有
 * NORMAL / REVERSED 等），不是单一枚举 —— 与 [MQConstants.MQENC_NATIVE] 相同
 * 显示 "Native"，否则分解各分量里的 REVERSED 位，无任何标志位则只给十六进制。
 */
private fun encodingDisplay(encoding: Int): String {
    val flags = buildList {
        if (encoding and MQConstants.MQENC_INTEGER_REVERSED != 0) add("Integer reversed")
        if (encoding and MQConstants.MQENC_DECIMAL_REVERSED != 0) add("Decimal reversed")
        val floatRepr = encoding and MQConstants.MQENC_FLOAT_MASK
        when (floatRepr) {
            MQConstants.MQENC_FLOAT_IEEE_REVERSED -> add("Float IEEE reversed")
            MQConstants.MQENC_FLOAT_S390 -> add("Float S390")
        }
    }
    if (encoding == MQConstants.MQENC_NATIVE) return "$encoding (0x${encoding.toString(16)}, Native)"
    if (flags.isEmpty()) return "$encoding (0x${encoding.toString(16)})"
    return "$encoding (0x${encoding.toString(16)}, ${flags.joinToString(", ")})"
}

/** MQMD.MsgType → 展示值："1 (REQUEST)"；未知只显示数字。 */
private fun msgTypeDisplay(msgType: Int): String = when (msgType) {
    MQConstants.MQMT_REQUEST -> "$msgType (REQUEST)"
    MQConstants.MQMT_REPLY -> "$msgType (REPLY)"
    MQConstants.MQMT_REPORT -> "$msgType (REPORT)"
    MQConstants.MQMT_DATAGRAM -> "$msgType (DATAGRAM)"
    else -> msgType.toString()
}

/** MQMD.Persistence → 展示值："1 (PERSISTENT)"。 */
private fun persistenceDisplay(persistence: Int): String = when (persistence) {
    MQConstants.MQPER_PERSISTENT -> "$persistence (PERSISTENT)"
    MQConstants.MQPER_NOT_PERSISTENT -> "$persistence (NOT PERSISTENT)"
    else -> persistence.toString()
}

/**
 * MQMessage → [MessageDetail]（service 侧组装，UI 不碰 [MQConstants]）。
 * [queueName] 进 Header（JMSDestination 的 MQMD 对应物就是消息所在队列）。
 */
internal fun MQMessage.toMessageDetail(queueName: String): MessageDetail {
    val (rawDate, rawTime) = putDateTime.toRawPutDateParts()
    // MQMessage.dataLength 是「剩余未读字节数」：readFully 消费后归零（下载链路
    // 先读后读的同款语义），必须先捕获总长再读消息体，否则字段恒为 0。
    val length = dataLength
    val bytes = ByteArray(length).also { if (it.isNotEmpty()) readFully(it) }
    val formattedPutTime = putDateTime?.let {
        "%04d-%02d-%02d %02d:%02d:%02d".format(
            it.get(java.util.Calendar.YEAR),
            it.get(java.util.Calendar.MONTH) + 1,
            it.get(java.util.Calendar.DAY_OF_MONTH),
            it.get(java.util.Calendar.HOUR_OF_DAY),
            it.get(java.util.Calendar.MINUTE),
            it.get(java.util.Calendar.SECOND),
        )
    } ?: "-"
    val replyTo = listOf(replyToQueueManagerName, replyToQueueName)
        .filter { it.isNotBlank() }
        .joinToString("/")

    fun h(key: String, value: String): Pair<String, String> = key to value

    return MessageDetail(
        messageId = messageId.toMqIdHex(),
        correlationId = correlationId.toMqIdHex(),
        content = bytes,
        dataLength = length,
        encoding = encoding,
        codedCharSetId = characterSet,
        msgType = messageType,
        putDate = rawDate,
        putTime = rawTime,
        priority = priority,
        persistence = persistence,
        headers = listOf(
            h("msg_detail_h_queue", queueName),
            h("msg_detail_h_msgid", messageId.toMqIdHex()),
            h("msg_detail_h_correlid", correlationId.toMqIdHex()),
            h("msg_detail_h_puttime", formattedPutTime),
            h("msg_detail_h_msgtype", msgTypeDisplay(messageType)),
            h("msg_detail_h_persistence", persistenceDisplay(persistence)),
            h("msg_detail_h_priority", priority.toString()),
            h("msg_detail_h_format", format.ifBlank { "-" }),
            h("msg_detail_h_ccsid", codedCharSetIdDisplay(characterSet)),
            h("msg_detail_h_encoding", encodingDisplay(encoding)),
            h("msg_detail_h_datalength", length.toString()),
            h("msg_detail_h_replyto", replyTo.ifBlank { "-" }),
            h("msg_detail_h_putapp", putApplicationName.ifBlank { "-" }),
            h("msg_detail_h_userid", userId.ifBlank { "-" }),
            h("msg_detail_h_backout", backoutCount.toString()),
            h("msg_detail_h_expiry", expiryDisplay(expiry)),
        ),
    )
}

/** MQMD.Expiry → 展示值（十分之一秒单位；MQEI_UNLIMITED = -1）。 */
private fun expiryDisplay(expiry: Int): String = when (expiry) {
    MQConstants.MQEI_UNLIMITED -> "$expiry (UNLIMITED)"
    else -> "${expiry / 10.0}s"
}
