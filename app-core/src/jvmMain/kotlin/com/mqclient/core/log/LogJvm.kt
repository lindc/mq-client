package com.mqclient.core.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** `HH:mm:ss.SSS`；`DateTimeFormatter` 不可变、线程安全，复用一份。 */
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/**
 * [emitPlatformLogLine] 的 JVM 实现：把一行日志写到控制台。
 *
 * - 时间戳用 `HH:mm:ss.SSS`（本地时区，短格式）—— 不进行内日期，单次运行不会长到
 *   需要日期标记的程度；
 * - WARN / ERROR 走 stderr、DEBUG / INFO 走 stdout，与仓库现有约定一致
 *   （println 记过程、System.err 记错误）；
 * - `runCatching` 是最后一道保险：`PrintStream` 本身已吞 IO 异常，这里保证「写入
 *   内核绝不抛」这条契约在替换流实现时也成立；
 * - 将来加文件 sink 时就在这个 actual 里做（并补一把锁保证整行原子），公开 API 不用动。
 */
internal actual fun emitPlatformLogLine(
    timestampMillis: Long,
    level: LogLevel,
    module: String,
    message: String,
) {
    val line = formatLogLine(timestampMillis, level, module, message)
    runCatching {
        if (level >= LogLevel.WARN) System.err.println(line) else println(line)
    }
}

/** `HH:mm:ss.SSS [LEVEL] [module] message` —— 行格式的唯一出处（测试锁定形状）。 */
internal fun formatLogLine(
    timestampMillis: Long,
    level: LogLevel,
    module: String,
    message: String,
): String {
    val time = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault()).toLocalTime()
    return "${TIME_FORMATTER.format(time)} [$level] [$module] $message"
}
