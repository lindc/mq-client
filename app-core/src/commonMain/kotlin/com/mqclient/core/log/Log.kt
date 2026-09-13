package com.mqclient.core.log

import com.mqclient.core.event.systemTimeMillis
import kotlin.concurrent.Volatile

/**
 * 日志级别。声明顺序即严重程度（DEBUG < INFO < WARN < ERROR），[Log.minLevel] 按它过滤。
 */
enum class LogLevel {
    /** 开发诊断信息（高频、只有排查时有用）。 */
    DEBUG,

    /** 正常生命周期节点（模块初始化、扫描完成…）。 */
    INFO,

    /** 能继续跑、但值得注意的异常（重试、降级）。 */
    WARN,

    /** 功能已不可用 / 数据有风险的错误。 */
    ERROR,
}

/**
 * 平台写入内核：把一行日志按平台能力写出去。
 *
 * 刻意做成**最小内核** —— 时间戳格式化、流向选择这类平台相关的事情都留在 actual 里；
 * commonMain 里没有 `System.err` / 文件 API（`println` 除外），所以物理写入必须借平台，
 * 这是 app-core 既有的 expect/actual 模式（见 EncryptionService / DriverFactory /
 * [systemTimeMillis]）。
 *
 * 实现必须**绝不抛异常**：打日志绝不能把业务搞挂。
 */
internal expect fun emitPlatformLogLine(
    timestampMillis: Long,
    level: LogLevel,
    module: String,
    message: String,
)

/**
 * 一行日志的写入出口。生产实现走 [emitPlatformLogLine]；测试替换成捕获实现，
 * 这样级别过滤、参数透传这些策略可以不起进程地验证。
 */
internal fun interface LogWriter {
    fun write(timestampMillis: Long, level: LogLevel, module: String, message: String)
}

/**
 * 全应用统一的日志入口。模块这样用：
 *
 * ```
 * Log.info("ibm-mq", "scan completed: queues=12")
 * Log.log("ibm-mq", LogLevel.ERROR, "connect failed: ${'$'}{error.message}")
 *
 * // 模块内的推荐姿势：模块名定义在模块自己里，句柄收走 tag（见 [Log.withTag]）
 * internal val moduleLog = Log.withTag(MODULE_ID)
 * moduleLog.info("scan completed: queues=12")
 * ```
 *
 * 设计要点：
 *  1. **API 在 common、写入在平台**：调用方只依赖 app-core 的公开 API，实际落点
 *     （终端 / 将来的文件 sink）由 JVM actual 决定，和 EncryptionService 同一套模式；
 *  2. **不走事件总线**：日志要求不丢、保序、无背压，并且不能形成「handler 出错 → 打
 *     日志 → 日志又是事件 → 又触发 handler」的风暴 —— 这些和 EventBus 的语义
 *     （replay = 0 会丢、慢订阅者会反压发送方）正好相反，理由详见 EventBus 的
 *     errorReporter 注释。需要别的组件**立刻反应**的事才走总线；事后要翻的记录走这里；
 *  3. **绝不抛、不做挂起**：写入是同步的一行 I/O（println 级别），桌面应用的日志量
 *     不需要为它引入异步管道；调用线程直接写，行内不会和别的调用穿插；
 *  4. 级别过滤是全局开关：[minLevel] 默认 DEBUG（等价于原先散装 println 的行为）。
 *
 * 线程安全：对象可在任意线程调用；单次 `println` 对 `PrintStream` 是原子的，
 * 所以控制台上的两行日志不会互相穿插。将来加文件 sink 时在 actual 里自行加锁。
 */
object Log {

    /**
     * 低于这个级别的调用直接丢弃。全局开关，默认 [LogLevel.DEBUG] = 全部输出。
     *
     * `@Volatile`：写可能发生在 UI / 设置线程，读发生在任意业务线程。
     */
    @Volatile
    var minLevel: LogLevel = LogLevel.DEBUG

    /**
     * 写入出口。`internal` + 可替换是留给测试的缝
     * （与 `ModuleRegistry.loadFrom` 的可注入迭代器同一思路），生产代码不需要碰它。
     */
    @Volatile
    internal var writer: LogWriter = LogWriter { timestamp, level, module, message ->
        emitPlatformLogLine(timestamp, level, module, message)
    }

    /**
     * 统一入口：`Log.log(module, level, message)`。
     *
     * [module] 用模块 id / 组件名（`ibm-mq`、`event-bus`、`core.config`），沿用仓库
     * 现有的 `[模块名]` 行内 tag 约定；[message] 不要求换行安全 —— 格式化归写入端。
     */
    fun log(module: String, level: LogLevel, message: String) {
        if (level < minLevel) return
        // 读一次快照：运行中（测试）替换 writer 时不会一半新一半旧。
        val target = writer
        target.write(systemTimeMillis(), level, module, message)
    }

    /** [log] 的 DEBUG 便捷方法。 */
    fun debug(module: String, message: String) = log(module, LogLevel.DEBUG, message)

    /** [log] 的 INFO 便捷方法。 */
    fun info(module: String, message: String) = log(module, LogLevel.INFO, message)

    /** [log] 的 WARN 便捷方法。 */
    fun warn(module: String, message: String) = log(module, LogLevel.WARN, message)

    /** [log] 的 ERROR 便捷方法。 */
    fun error(module: String, message: String) = log(module, LogLevel.ERROR, message)

    /**
     * 绑定模块 tag 的日志句柄：模块在**自己内部**定义模块名，由它派生一个句柄，
     * 之后调用点不再手写模块名 —— 名字只出现一次，也就没有写错的机会：
     *
     * ```
     * internal val ibmMqLog = Log.withTag(MODULE_ID)   // MODULE_ID 是模块自己的常量
     * ibmMqLog.info("scan completed: queues=12")
     * ```
     *
     * app-core **不登记任何模块名**：[withTag] 只是收下调用方给的字符串，
     * 新增模块不需要改动 app-core（零注册设计的一部分）。
     */
    fun withTag(module: String): ModuleLogger = ModuleLogger(module)
}

/**
 * 一个模块 / 组件的日志句柄，由 [Log.withTag] 创建 —— 把 tag 参数从每个调用点收走。
 * 无状态，线程安全（只是转发给 [Log]）。
 */
class ModuleLogger internal constructor(
    /** 本句柄的模块 tag，等同 [Log.log] 的第一个参数。 */
    val module: String,
) {

    /** 带级别的统一入口。 */
    fun log(level: LogLevel, message: String) = Log.log(module, level, message)

    /** [log] 的 DEBUG 便捷方法。 */
    fun debug(message: String) = log(LogLevel.DEBUG, message)

    /** [log] 的 INFO 便捷方法。 */
    fun info(message: String) = log(LogLevel.INFO, message)

    /** [log] 的 WARN 便捷方法。 */
    fun warn(message: String) = log(LogLevel.WARN, message)

    /** [log] 的 ERROR 便捷方法。 */
    fun error(message: String) = log(LogLevel.ERROR, message)
}
