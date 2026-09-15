package com.mqclient.core.log

import com.mqclient.core.ConfigManager
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** `HH:mm:ss.SSS`；`DateTimeFormatter` 不可变、线程安全，复用一份。 */
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/**
 * 文件行的格式：控制台行不带日期（单次运行看不到跨天），但日志文件跨天累积，
 * 排查昨天的记录没有日期无从下手，所以文件行带完整日期。
 */
private val FILE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

/** 日志目录：`configDir()/logs/`，随 `mqclient.config.dir` 覆盖一起走（测试/便携模式不碰真实目录）。 */
private const val LOG_DIR_NAME = "logs"
private const val LOG_FILE_NAME = "mqclient.log"

/** 轮转产物：当前文件写满后整体改名成 `.1`，旧的 `.1` 直接丢（只保留一代，简单够用）。 */
private const val ROTATED_LOG_FILE_NAME = "mqclient.log.1"

/** 单个日志文件上限（字节）。超过即轮转 —— 按 5MB 算，一代最多占 10MB 磁盘。 */
private const val MAX_LOG_FILE_BYTES = 5L * 1024 * 1024

/**
 * [emitPlatformLogLine] 的 JVM 实现：写控制台 + 写文件 sink。
 *
 * - 时间戳用 `HH:mm:ss.SSS`（本地时区，短格式）—— 不进行内日期，单次运行不会长到
 *   需要日期标记的程度；
 * - WARN / ERROR 走 stderr、DEBUG / INFO 走 stdout，与仓库现有约定一致
 *   （println 记过程、System.err 记错误）；
 * - `runCatching` 是最后一道保险：`PrintStream` 本身已吞 IO 异常，这里保证「写入
 *   内核绝不抛」这条契约在替换流实现时也成立；
 * - 文件 sink 见 [LogFileSink]：追加写 + 大小轮转，任何失败只静默放弃文件、
 *   不影响控制台。刻意不引 SLF4J/logback —— 全仓没有传递依赖要求它，而自研
 *   facade 的 JVM actual 换起来成本极低。
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
    LogFileSink.write(timestampMillis, level, module, message)
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

/**
 * 进程级文件 sink：把每行日志追加到 `configDir()/logs/mqclient.log`。
 *
 * 设计要点：
 *  - **绝不抛、不阻塞语义**：所有 IO 包在 try/catch 里，任何一次失败（目录建不出、
 *     磁盘满、权限）都永久禁用文件输出（[disabled]）—— 控制台照常，不要为坏掉的
 *     文件路径在每行日志上重试打转；
 *  - **整行原子**：调用线程直接持锁同步写。日志量是「一行 println」级别，异步管道
 *     不值得；锁保证跨线程的行不互相穿插；
 *  - **轮转**：只在打开文件和写满 [MAX_LOG_FILE_BYTES] 时检查（记账 [bytesWritten]，
 *     不逐行 stat 文件大小）。轮转 = 关流 → 删旧 `.1` → 当前文件改名 `.1` → 下次
 *     写入时重新打开。只保留一代，防止无限吃磁盘；
 *  - **文件行带日期**（`yyyy-MM-dd` 前缀）：文件跨运行累积，控制台行格式保持不变。
 *
 * 线程安全：所有可变状态收在 [lock] 后面；[disabled] 只在持锁时写、读走快速路径，
 * 用 `@Volatile` 保证可见性。
 */
private object LogFileSink {

    private val lock = Any()

    /** 一次失败即永久禁用（快速路径跳过锁外的无效调用）。 */
    @Volatile
    private var disabled = false

    private var stream: PrintStream? = null
    private var bytesWritten = 0L

    fun write(timestampMillis: Long, level: LogLevel, module: String, message: String) {
        if (disabled) return
        synchronized(lock) {
            try {
                val out = stream ?: open()
                val time = Instant.ofEpochMilli(timestampMillis)
                    .atZone(ZoneId.systemDefault()).toLocalDateTime()
                val fileLine = "${FILE_TIME_FORMATTER.format(time)} [$level] [$module] $message"
                out.println(fileLine)
                bytesWritten += fileLine.toByteArray(StandardCharsets.UTF_8).size + 1
                if (bytesWritten >= MAX_LOG_FILE_BYTES) {
                    out.close()
                    stream = null
                    rotate()
                }
            } catch (_: Exception) {
                runCatching { stream?.close() }
                stream = null
                disabled = true
            }
        }
    }

    /** 追加模式打开日志文件；若磁盘上的现有文件已超限，先轮转再开新文件。 */
    private fun open(): PrintStream {
        val file = logFile()
        if (Files.exists(file) && Files.size(file) >= MAX_LOG_FILE_BYTES) rotate()
        Files.createDirectories(file.parent)
        val out = PrintStream(
            Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
            true,
            StandardCharsets.UTF_8,
        )
        bytesWritten = if (Files.exists(file)) Files.size(file) else 0L
        stream = out
        return out
    }

    /** 关掉当前文件 → 删旧 `.1` → 当前改名 `.1`。调到它时 [stream] 必须已关闭置空。 */
    private fun rotate() {
        val current = logFile()
        val rotated = current.resolveSibling(ROTATED_LOG_FILE_NAME)
        Files.deleteIfExists(rotated)
        if (Files.exists(current)) {
            Files.move(current, rotated, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun logFile(): Path =
        ConfigManager.configDir().toFile().toPath().resolve(LOG_DIR_NAME).resolve(LOG_FILE_NAME)
}
