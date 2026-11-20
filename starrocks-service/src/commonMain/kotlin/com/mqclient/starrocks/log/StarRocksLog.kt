package com.mqclient.starrocks.log

import com.mqclient.core.log.Log
import com.mqclient.core.log.LogLevel

/**
 * StarRocks 模块的日志入口：统一 tag、脱敏、截断与耗时打点的**唯一出口**。
 *
 * 为什么要这一层（而不是各处直接调 `Log.info("...")`）：
 *  - **tag 只有一份定义**：`[StarRocks][Conn]` / `[SQL]` / `[Meta]` / `[Profile]`
 *    写错一个字符，日志就按另一个模块归类，事后 grep 找不到 —— 这里集中定义，
 *    调用点只选「哪条链路」；
 *  - **脱敏与截断是机制，不是自觉**：[redactJdbcUrl] 在拼接前抹掉密码，
 *    [truncate] 在 500 字符处截断；调用方传原始串即可，不会因为某个人忘了处理
 *    而泄漏凭据或把整段 Profile 灌进日志；
 *  - **性能打点格式固定**：`[MQ-Perf][StarRocks] action=..., type=..., cost=Xms`
 *    （与 MQ 系 `[MQ-Perf]` 同口径），由 [perf] 一处生成。
 *
 * 底层始终是 `app-core` 的 [Log]：不引第三方框架、不自己写文件 sink。
 *
 * 级别约定（本模块统一执行）：
 *  - [LogLevel.INFO]：正常流程节点（连接建立 / 关键 SQL 执行 / 元数据加载 / Profile 拉取）；
 *  - [LogLevel.DEBUG]：详尽的内部解析细节（原始列名、逐行丢弃、语句生命周期）；
 *  - [LogLevel.WARN]：抛给用户的业务异常（连不上、查不到 Profile、元数据形状不符），
 *    功能仍可继续；
 *  - [LogLevel.ERROR]：功能已中断的严重异常，**必须带完整异常栈**（[emit] 自动追加）。
 */
object StarRocksLog {

    /** 模块前缀（所有 tag 的共同前缀）。 */
    const val MODULE: String = "[StarRocks]"

    /** 连接与生命周期。 */
    const val CONN: String = "$MODULE[Conn]"

    /** SQL 执行。 */
    const val SQL: String = "$MODULE[SQL]"

    /** 元数据解析（目录树 / 字段 / DDL / 分区）。 */
    const val META: String = "$MODULE[Meta]"

    /** Profile 采集与降级链路。 */
    const val PROFILE: String = "$MODULE[Profile]"

    /** 性能打点 tag（与 MQ 系 `[MQ-Perf]` 同口径，按协议区分）。 */
    const val PERF: String = "[MQ-Perf][StarRocks]"

    /**
     * SQL / 原始返回串的截断阈值。
     *
     * 500 是权衡：一条普通元数据 SQL 与 ResultSet 前几行的形状摘要都装得下，
     * 又不会让 Profile 那种几十万字符的报告把日志文件写爆（文件 sink 单文件 5MB 轮转）。
     */
    const val TRUNCATE_LIMIT: Int = 500

    /* ------------------------------ 连接 ------------------------------ */

    /** 连接链路 INFO。 */
    fun conn(message: String) = conn(LogLevel.INFO, message)

    /** 连接链路（显式级别；[error] 非空时追加完整异常栈）。 */
    fun conn(level: LogLevel, message: String, error: Throwable? = null) = emit(CONN, level, message, error)

    /* ------------------------------ SQL ------------------------------ */

    /** SQL 链路 INFO。 */
    fun sql(message: String) = sql(LogLevel.INFO, message)

    /** SQL 链路（显式级别）。 */
    fun sql(level: LogLevel, message: String, error: Throwable? = null) = emit(SQL, level, message, error)

    /* ------------------------------ 元数据 ------------------------------ */

    /** 元数据链路 INFO。 */
    fun meta(message: String) = meta(LogLevel.INFO, message)

    /** 元数据链路（显式级别）。 */
    fun meta(level: LogLevel, message: String, error: Throwable? = null) = emit(META, level, message, error)

    /* ------------------------------ Profile ------------------------------ */

    /** Profile 链路 INFO。 */
    fun profile(message: String) = profile(LogLevel.INFO, message)

    /** Profile 链路（显式级别）。 */
    fun profile(level: LogLevel, message: String, error: Throwable? = null) = emit(PROFILE, level, message, error)

    /* ------------------------------ 性能打点 ------------------------------ */

    /**
     * 一条耗时打点：`[MQ-Perf][StarRocks] action=execute_sql, type=PROFILE, cost=124ms`。
     *
     * @param action 动作名（执行 / 拉取等，见调用点）。
     * @param type 语句 / 任务类型（[statementKind] 的产物，如 SELECT / PROFILE / DDL）。
     * @param costMs 耗时（毫秒）。
     */
    fun perf(action: String, type: String, costMs: Long) {
        Log.info(PERF, perfLine(action, type, costMs))
    }

    /** [perf] 的消息体（纯函数，测试钉住格式）。 */
    fun perfLine(action: String, type: String, costMs: Long): String =
        "action=$action, type=$type, cost=${costMs}ms"

    /* ------------------------------ 工具 ------------------------------ */

    /**
     * 截断到 [limit] 字符并加 `...` 后缀（同时压掉换行，保证一条日志一行）。
     *
     * 原始长度追加在末尾（`total N chars`）：排查「是不是被截断掩盖了」时不用猜，
     * 一眼看出原文有多长、该不该去完整报告里找。
     */
    fun truncate(raw: String, limit: Int = TRUNCATE_LIMIT): String {
        val singleLine = raw.replace(WHITESPACE, " ").trim()
        if (singleLine.length <= limit) return singleLine
        return singleLine.take(limit) + "... (total ${singleLine.length} chars)"
    }

    /**
     * JDBC URL 脱敏：抹掉 `password=...` 等参数与 `//user:pass@` 里的凭据。
     *
     * 为什么必须有：`jdbcUrlOverride` 允许用户塞任意连接参数，Connector/J 支持
     * `user` / `password` 直接写在 URL 里 —— 直接把 URL 打进日志就等于明文落盘。
     */
    fun redactJdbcUrl(url: String): String =
        url.replace(PASSWORD_PARAM) { "${it.groupValues[1]}=***" }
            .replace(USER_INFO, "//***@")

    /**
     * 语句类型（打点的 `type` 字段，同时让「哪类语句慢」一眼可分）。
     *
     * 判定只取首个非空 token，不看全文 —— 日志不该为分类去解析 SQL。
     */
    fun statementKind(sql: String): String {
        val first = sql.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("--") && !it.startsWith("#") }
            ?.split(WHITESPACE)
            ?.firstOrNull()
            ?.trimStart('(')
            ?.trimEnd(';', ',', ')')
            ?.uppercase()
            .orEmpty()
        val upperSql = sql.uppercase()
        // PROFILE 只认「取 Profile」的语句：ANALYZE PROFILE / SHOW PROFILELIST /
        // SELECT get_query_profile(...)。`SET enable_profile = true` 是会话开关，
        // 不能因为含 PROFILE 字样就被归到 PROFILE（那会让打点严重失真）。
        val profileStatement = when {
            first == "ANALYZE" || first == "SHOW" ->
                upperSql.contains("PROFILE")
            first == "SELECT" || first == "WITH" ->
                upperSql.contains("GET_QUERY_PROFILE")
            first.startsWith("GET_QUERY_PROFILE") -> true
            else -> false
        }
        return when {
            first.isEmpty() -> "EMPTY"
            profileStatement -> "PROFILE"
            first == "SELECT" || first == "WITH" -> "SELECT"
            first == "SHOW" -> "SHOW"
            first == "EXPLAIN" -> "EXPLAIN"
            first == "SET" || first == "USE" -> "SESSION"
            first == "INSERT" || first == "UPDATE" || first == "DELETE" -> "DML"
            first == "CREATE" || first == "DROP" || first == "ALTER" ||
                first == "TRUNCATE" || first == "RENAME" -> "DDL"
            else -> "OTHER"
        }
    }

    /** SQL 日志的固定前缀：`type=<kind> sql=<truncated>`，调用方只补上下文。 */
    fun sqlSummary(sql: String): String = "type=${statementKind(sql)} sql=${truncate(sql)}"

    /* ------------------------------ 内部 ------------------------------ */

    /**
     * 统一写出：WARN / ERROR 追加分类信息由调用方拼好；ERROR **强制**附完整栈。
     *
     * 打日志绝不能把业务搞挂：`Log` 自身已保证不抛，这里再兜一层 `runCatching`
     * 是为了防格式化本身出意外（例如异常栈格式化在极端栈深度下失败）。
     */
    private fun emit(module: String, level: LogLevel, message: String, error: Throwable?) {
        runCatching {
            val text = if (error != null && level >= LogLevel.WARN) {
                "$message\n${error.stackTraceToString()}"
            } else {
                message
            }
            Log.log(module, level, text)
        }
    }

    private val WHITESPACE = Regex("\\s+")

    /** `password=...` / `passwd=...` / `pwd=...` 连接参数（大小写不敏感）。 */
    private val PASSWORD_PARAM = Regex("(?i)(password|passwd|pwd)=([^&;\\s]*)")

    /**
     * URL 的 userinfo 段（`user:pass@` 部分）。
     *
     * 注意别把替换结果写成字面量注释：Kotlin 的块注释会嵌套，
     * 注释里出现斜杠加星号就会开启一层新注释、把后面的代码吞掉。
     */
    // 贪婪匹配到 authority 段**最后一个** `@`：密码本身可能含 `@`
    // （`//user:p@ss@host`），非贪婪版本只会抹掉 `user:p`，把 `ss@host` 留在日志里。
    // 不会误伤路径/参数里的 `@` —— `[^/\s]` 在第一个 `/` 处就停下。
    private val USER_INFO = Regex("//([^/\\s]+)@")
}
