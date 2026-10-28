package com.mqclient.mqui.file

import java.io.File

/**
 * 单个消息体文件的大小上限。
 *
 * 100MB 是「防误选」的保险，不是协议上限：四种协议各自允许的最大单条消息都更小
 * 或与它同级（IBM MQ 默认 `MAXMSGL` 4MB、Kafka `max.request.size` 默认 1MB、
 * RocketMQ 默认 4MB、RabbitMQ 受 frame 与内存约束），所以正常业务文件不会被它
 * 拦住；而「在文件选择器里点错一个几十 GB 的备份文件」会被立刻拒绝，而不是先
 * 把它整个读进堆（`File.readBytes()` 没有任何流控，一个误选就是一次 OOM）。
 *
 * 与 [MAX_COMMAND_FILE_BYTES] 分开：那是**文本命令脚本**的编辑上限，量级差一个
 * 数量级，共用一个常量只会让改一方时误伤另一方。
 */
const val MAX_MESSAGE_FILE_BYTES: Long = 100L * 1024 * 1024

/** [MAX_MESSAGE_FILE_BYTES] 换算成 MB，给用户文案用。 */
const val MAX_MESSAGE_FILE_MB: Long = 100L

/**
 * 命令控制台「打开文件」的文本大小上限（10MB）。
 *
 * 上限的意义不是防协议限制（MQSC 脚本没有长度要求），而是防 UI 卡死：整个文件
 * 会进编辑器、逐行建渲染对象，几十万行会让组合与布局直接不可用。
 */
const val MAX_COMMAND_FILE_BYTES: Long = 10L * 1024 * 1024

/** 命令脚本上限换算成 KB，给用户文案用（与既有提示文案的单位一致）。 */
const val MAX_COMMAND_FILE_KB: Long = MAX_COMMAND_FILE_BYTES / 1024

/** 文件超过 [maxBytes] 时抛出 —— UI 据此给出「文件过大」而不是笼统的「读取失败」。 */
class MessageFileTooLargeException(
    val fileName: String,
    val sizeBytes: Long,
    val maxBytes: Long,
) : IllegalArgumentException("$fileName: ${sizeBytes}B exceeds ${maxBytes}B")

/** 一个没能进入发送列表的文件；[tooLarge] 区分「太大」与「读不出来」两种原因。 */
data class MessageFileFailure(
    val fileName: String,
    val tooLarge: Boolean = false,
) {
    companion object {
        /** 从异常归类：只有 [MessageFileTooLargeException] 算「太大」，其余都算读失败。 */
        fun of(fileName: String, error: Throwable): MessageFileFailure =
            MessageFileFailure(fileName, tooLarge = error is MessageFileTooLargeException)
    }
}

/**
 * 读取一个消息体文件，**先看尺寸再读**。
 *
 * 顺序是关键：[File.length] 只做一次 `stat`，而 [File.readBytes] 会按文件大小
 * 一次性分配数组 —— 先读后判的话，「超过上限」这条错误要等到整个文件已经进堆
 * 才抛得出来，守卫本身就失去了意义。
 *
 * 读完之后再判一次：`length()` 与 `readBytes()` 之间文件可能被替换 / 继续写入
 * （TOCTOU），真正进内存的字节数才是可信的。
 *
 * 读不出来（不存在 / 无权限）抛 [java.io.IOException]，由调用方按「读失败」归类。
 */
fun File.readMessageBytes(maxBytes: Long = MAX_MESSAGE_FILE_BYTES): ByteArray {
    val declared = length()
    if (declared > maxBytes) throw MessageFileTooLargeException(name, declared, maxBytes)
    val bytes = readBytes()
    if (bytes.size.toLong() > maxBytes) {
        throw MessageFileTooLargeException(name, bytes.size.toLong(), maxBytes)
    }
    return bytes
}
