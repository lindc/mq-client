package com.mqclient.ibmmq.messaging

import com.ibm.mq.constants.MQConstants
import java.util.GregorianCalendar
import java.util.Locale

/**
 * dmpmqmsg 文件格式的生成与解析（IBM MQ 官方 queue load/unload 工具的文件格式）。
 *
 * 语法按真实 dmpmqmsg 9.2.4.0 输出逐行校准（fixture：QM1/DEV.QUEUE.1 实测导出）：
 *
 * ```
 * * DMPMQMSG Version:9.2.4.0 Created:Fri Oct  2 12:20:12 2026
 * * Qmgr  = QM1
 * * Queue = DEV.QUEUE.1
 *
 * A VER 2
 * A RPT 0
 * ...（28 个 MQMD 属性，序同 amqsbcg / IBM 文档映射表）
 * A ORL -1
 * X 48656C6C6F...            ← 数据行："X " + 大写 hex，每行 32 字节，末行可短
 * <空行>                     ← 消息分隔
 * ```
 *
 * 值形态（与样例一致）：
 *  - 数值域十进制（EXP / ORL 可为 -1 = 无限 / 未定义）；
 *  - 字节域十六进制：MSI/COI/GRP 48 字符（24 字节）、ACC/AIX 64 字符（32 字节）、
 *    AOX 8 字符（4 字节，char 域按字节转储）；
 *  - 字符域原样带 MQMD padding 输出：FMT 8、USR 12、PAN 28、RTQ/RTM 48
 *    （空值 = 全空格）；解析端 trimEnd，两端不敏感。
 *
 * 解析为**宽松行解析**：只认 `A `（属性）与 `X `（hex 数据）前缀，`*` 注释与其他
 * 行忽略，空行结束当前消息 —— 因此也能读 -dH（无文件头）产物与手工编辑过的文件。
 * 解析出 0 条消息即「不是 dmpmqmsg 格式」，由调用方走 fallback。
 */
object DmpmqmsgFormat {

    /** 文件头第一行前缀（导出与格式识别的锚点）。 */
    const val BANNER_PREFIX = "* DMPMQMSG"

    /** 每行数据的字节数（样例实测：50 hex 字符 = 25 字节/行）。 */
    private const val DATA_BYTES_PER_LINE = 25

    /* ------------------------------ 导出 ------------------------------ */

    /**
     * [MessageDetail] → dmpmqmsg 格式文本（单条消息）。
     *
     * @param qmgrName / qmgrVersion 文件头注释用（慢扫缓存的 QMGR 快照可得）；
     *   缺失时写 unknown —— loader 对注释行内容不敏感，锚点是 `* DMPMQMSG` 前缀。
     */
    fun export(detail: MessageDetail, queueName: String, qmgrName: String?, qmgrVersion: String?): String {
        val now = GregorianCalendar()
        val created = String.format(
            Locale.ENGLISH,
            "%ta %tb %2te %tH:%tM:%tS %tY",
            now, now, now, now, now, now, now,
        )
        return buildString {
            appendLine("* DMPMQMSG Version:${qmgrVersion?.takeIf { it.isNotBlank() } ?: "unknown"} Created:$created")
            appendLine("* Qmgr  = ${qmgrName?.takeIf { it.isNotBlank() } ?: "unknown"}")
            appendLine("* Queue = $queueName")
            appendLine()
            fun attr(code: String, value: String) = appendLine("A $code $value")
            attr("VER", "2") // MQMD v2（现代 QM 上所有消息的 MQMD 版本）
            attr("RPT", detail.report.toString())
            attr("MST", detail.msgType.toString())
            attr("EXP", detail.expiry.toString())
            attr("FDB", detail.feedback.toString())
            attr("ENC", detail.encoding.toString())
            attr("CCS", detail.codedCharSetId.toString())
            attr("FMT", pad(detail.format, 8))
            attr("PRI", detail.priority.toString())
            attr("PER", detail.persistence.toString())
            attr("MSI", detail.messageId)
            attr("COI", detail.correlationId)
            attr("BOC", detail.backoutCount.toString())
            attr("RTQ", pad(detail.replyToQueue, 48))
            attr("RTM", pad(detail.replyToQMgr, 48))
            attr("USR", pad(detail.userIdentifier, 12))
            attr("ACC", detail.accountingTokenHex)
            attr("AIX", detail.applIdentityDataHex)
            attr("PAT", detail.putApplType.toString())
            attr("PAN", pad(detail.putApplicationName, 28))
            attr("PTD", detail.putDate)
            attr("PTT", detail.putTime)
            attr("AOX", detail.applOriginDataHex)
            attr("GRP", detail.groupIdHex)
            attr("MSQ", detail.msgSeqNumber.toString())
            attr("OFF", detail.offset.toString())
            attr("MSF", detail.msgFlags.toString())
            attr("ORL", detail.originalLength.toString())
            // 消息体为空（dataLength == 0）照常导出，只是没有 X 行
            detail.content.toList().chunked(DATA_BYTES_PER_LINE).forEach { chunk ->
                appendLine("X " + chunk.joinToString("") { "%02X".format(it) })
            }
            appendLine()
        }
    }

    /** 字符域按 MQMD 字段宽度补空格（样例形态：原样输出未 trim 的 char 域）。 */
    private fun pad(value: String, width: Int): String =
        value.padEnd(width).take(width)

    /* ------------------------------ 解析 ------------------------------ */

    fun parse(text: String): DmpmqmsgParseResult {
        val messages = mutableListOf<DmpmqmsgMessage>()
        var attrs = LinkedHashMap<String, String>()
        val hex = StringBuilder()

        fun flush() {
            if (attrs.isNotEmpty()) {
                messages += DmpmqmsgMessage(attrs, decodeHex(hex.toString()))
                attrs = LinkedHashMap()
                hex.setLength(0)
            }
        }

        for (line in text.lineSequence()) {
            when {
                line.startsWith("A ") -> {
                    val rest = line.substring(2)
                    val code = rest.take(3)
                    if (code.isNotBlank() && code.all { it.isLetterOrDigit() }) {
                        attrs[code] = rest.substring(3).trimEnd()
                    }
                }
                line.startsWith("X ") -> hex.append(line.substring(2).trim())
                line.isBlank() -> flush()
                // '* ' 注释（文件头）与其他行：忽略
            }
        }
        flush()
        return DmpmqmsgParseResult(messages)
    }

    private fun decodeHex(hex: String): ByteArray {
        val clean = hex.filter { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}

/** 一次 dmpmqmsg 文件解析的结果（可能包含多条消息）。 */
data class DmpmqmsgParseResult(
    val messages: List<DmpmqmsgMessage>,
)

/**
 * 一条从 dmpmqmsg 文件解析出的消息：原始属性表（三字母码 → 值，已 trimEnd）+ 消息体。
 * 常用属性以强类型访问器暴露；码缺失（手工编辑过）时数值域为 null，由调用方给默认值。
 */
data class DmpmqmsgMessage(
    val attrs: Map<String, String>,
    val body: ByteArray,
) {
    val msgIdHex: String get() = attrs["MSI"].orEmpty().trim()
    val correlIdHex: String get() = attrs["COI"].orEmpty().trim()

    /** CorrelId 是否有值（全 0 = 空，导入时不还原，由 MQ 生成）。 */
    val hasCorrelId: Boolean get() = correlIdHex.isNotBlank() && correlIdHex.any { it != '0' }

    val correlIdBytes: ByteArray? get() = if (hasCorrelId) correlIdHex.hexToBytes() else null
    val msgType: Int? get() = intOf("MST")
    val persistence: Int? get() = intOf("PER")
    val priority: Int? get() = intOf("PRI")
    val expiry: Int? get() = intOf("EXP")
    val ccsid: Int? get() = intOf("CCS")
    val format: String get() = attrs["FMT"]?.trim().orEmpty()
    val replyToQueue: String get() = attrs["RTQ"]?.trim().orEmpty()
    val replyToQMgr: String get() = attrs["RTM"]?.trim().orEmpty()
    val putDate: String get() = attrs["PTD"]?.trim().orEmpty()
    val putTime: String get() = attrs["PTT"]?.trim().orEmpty()

    private fun intOf(code: String): Int? = attrs[code]?.trim()?.toIntOrNull()

    private fun String.hexToBytes(): ByteArray =
        chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
}

/**
 * 导入发送参数（service 侧持有 MQConstants，UI 只拿计算结果）：码缺失或
 * fallback（attrs 为空表）时回落 MQ 默认 —— 持久 / 优先级 4 / 不过期。
 */
fun DmpmqmsgMessage.sendPersistence(): Int = persistence ?: MQConstants.MQPER_PERSISTENT

fun DmpmqmsgMessage.sendPriority(): Int = priority ?: 4

fun DmpmqmsgMessage.sendExpiryTenths(): Int = expiry ?: MQConstants.MQEI_UNLIMITED
