package com.mqclient.starrocks.ui

import com.mqclient.core.ServiceException
import com.mqclient.core.log.LogLevel
import com.mqclient.starrocks.jdbc.JdbcConnectTimeoutException
import com.mqclient.starrocks.jdbc.JdbcFailure
import com.mqclient.starrocks.log.StarRocksLog
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Query Profile 闭环的取数与解析（3.4.0 实测结构，测试钉住真实样例）：
 *
 * - `SHOW PROFILELIST;` → 最近查询列表（QueryId/StartTime/Time/State/Statement），
 *   **含已结束的查询** —— 这是「拿到 QueryId」最可靠的一跳；
 * - `ANALYZE PROFILE FROM '<QueryId>'` → 结构化**纯文本**报告（不是 JSON）：
 *   `Summary` 段（TotalTime / QueryPeakMemoryUsage / Top Most Time-consuming Nodes
 *   编号列表）+ `Fragment` 段算子树（OutputRows / SCAN 算子含 Table）。
 *
 * 全部按**列名 / 行前缀**解析，形状不符返回空值兜底，绝不崩面板。
 */
internal const val PROFILELIST_SQL = "SHOW PROFILELIST;"

/** 「最近查询」列表的一行（SHOW PROFILELIST 的一行）。 */
internal data class ProfileListItem(
    val queryId: String,
    val startTime: String,
    val time: String,
    val state: String,
    val statement: String,
)

/** 按列名解析 SHOW PROFILELIST（列缺失用 "-" 占位）。 */
internal fun parseProfileList(result: StarRocksQueryRows): List<ProfileListItem> {
    val cols = result.columns.map { it.lowercase() }
    fun idx(name: String): Int = cols.indexOf(name)

    val qidIdx = idx("queryid")
    val startIdx = idx("starttime")
    val timeIdx = idx("time")
    val stateIdx = idx("state")
    val stmtIdx = idx("statement")

    fun cell(row: List<Any?>, i: Int): String = row.getOrNull(i)?.toString().orEmpty().ifBlank { "-" }

    return result.rows.mapNotNull { row ->
        val queryId = cell(row, qidIdx)
        if (queryId == "-") return@mapNotNull null
        ProfileListItem(
            queryId = queryId,
            startTime = cell(row, startIdx),
            time = cell(row, timeIdx),
            state = cell(row, stateIdx),
            statement = cell(row, stmtIdx).takeIf { it != "-" } ?: "",
        )
    }
}

/** 一个耗时 Top 算子（Summary 段 "Top Most Time-consuming Nodes" 的一行）。 */
internal data class ProfileOperator(
    /** 展示名（含 id 与限定词，如 `OLAP_SCAN (id=0)` / `AGGREGATION (id=1) [finalize, update]`）。 */
    val name: String,
    /** 原样耗时字符串（如 `737.656us` / `3s0ms`），不换算单位。 */
    val time: String,
    /** 占比字符串（如 `58.36%`）。 */
    val pct: String,
)

/** Profile 摘要（弹窗顶部指标 + Top 算子表的数据源）。 */
internal data class ProfileSummary(
    val queryId: String,
    val state: String,
    val totalTime: String,
    /** 峰值内存：优先 QueryPeakMemoryUsage，为 ? 时回落 QueryAllocatedMemoryUsage；无值空串。 */
    val peakMemory: String,
    /** 扫描行数：算子树中所有 *SCAN 算子 OutputRows 之和；无扫描算子空串。 */
    val scanRows: String,
    val topOperators: List<ProfileOperator>,
)

/**
 * 解析 Profile 文本（自动识别两种格式，均为 3.4.0 真实输出）：
 * - `get_query_profile`（取数链首选）：`- 键: 值` 缩进风格，算子块为
 *   `NAME (plan_node_id=N):` + `- OperatorTotalTime`，扫描行数在 SCAN 块的
 *   `- PullRowNum: 10.008K (10008)`；
 * - `ANALYZE PROFILE`（兜底）：Summary 段 + `└──` 算子树。
 * 形状不符的字段一律空串兜底，绝不崩面板。
 */
internal fun parseProfileSummary(raw: String): ProfileSummary {
    // 纵深防御：即使调用方拿到的是未经清洗的原始文本，解析也不受 ANSI 控制符干扰。
    val clean = stripAnsiEscapes(raw)
    return if (clean.contains("- Query State:")) parseGetQueryProfileSummary(clean)
    else parseAnalyzeProfileSummary(clean)
}

private fun parseGetQueryProfileSummary(raw: String): ProfileSummary {
    val lines = raw.lines()

    /** Summary 段的 `- 键: 值` 字段（精确前缀，避免命中 Planner 的 `-- Total[1]`）。 */
    fun field(key: String): String = lines
        .firstOrNull { it.trim().startsWith("- $key: ") }
        ?.substringAfter(": ")?.trim().orEmpty()

    val queryId = field("Query ID")
    val state = field("Query State")
    val totalTime = field("Total")

    val peakValue = lines
        .firstOrNull { it.trim().startsWith("- QueryPeakMemoryUsagePerNode: ") }
        ?.substringAfter(": ")?.trim().orEmpty()
    val allocatedValue = lines
        .firstOrNull { it.trim().startsWith("- QueryAllocatedMemoryUsage: ") }
        ?.substringAfter(": ")?.trim().orEmpty()
    val peakMemory = when {
        peakValue.isNotEmpty() && peakValue != "?" -> peakValue
        allocatedValue.isNotEmpty() && allocatedValue != "?" -> allocatedValue
        else -> ""
    }

    // 算子块：`NAME (plan_node_id=N):` 头 + 块内 `- OperatorTotalTime` / `- PullRowNum`。
    class Op(val name: String) {
        var timeNanos = 0L
        var timeText = ""
        var pullRows: Long? = null
    }

    val headerRegex = Regex("""^(\S+) \(plan_node_id=-?\d+\):$""")
    val operators = mutableListOf<Op>()
    var current: Op? = null

    fun flush() {
        current?.let { operators += it }
        current = null
    }

    for (line in lines) {
        val trimmed = line.trim()
        headerRegex.matchEntire(trimmed)?.let { m ->
            flush()
            current = Op(m.groupValues[1])
            continue
        }
        val op = current ?: continue
        when {
            trimmed.startsWith("- OperatorTotalTime: ") -> {
                op.timeText = trimmed.substringAfter(": ").trim()
                op.timeNanos = timeToNanos(op.timeText)
            }

            trimmed.startsWith("- PullRowNum: ") && op.name.contains("SCAN") ->
                op.pullRows = parseRowCount(trimmed.substringAfter(": ").trim())
        }
    }
    flush()

    val topOperators = operators
        .filter { it.timeNanos > 0 }
        .sortedByDescending { it.timeNanos }
        .map { ProfileOperator(name = it.name, time = it.timeText, pct = "") }
    val scanRows = operators
        .filter { it.name.contains("SCAN") && it.pullRows != null }
        .sumOf { it.pullRows!! }
        .let { if (operators.any { op -> op.name.contains("SCAN") && op.pullRows != null }) it.toString() else "" }

    return ProfileSummary(queryId, state, totalTime, peakMemory, scanRows, topOperators)
}

/** `456.180us` / `3s16ms` / `2.469ms` / `0ns` → 纳秒（多单位段累加），无法解析返回 0。 */
internal fun timeToNanos(text: String): Long =
    Regex("""(\d+(?:\.\d+)?)(ns|us|ms|s)""").findAll(text).fold(0L) { acc, m ->
        val value = m.groupValues[1].toDouble()
        val scaled = when (m.groupValues[2]) {
            "ns" -> value
            "us" -> value * 1_000
            "ms" -> value * 1_000_000
            else -> value * 1_000_000_000
        }
        acc + scaled.toLong()
    }

/** `10.008K (10008)` → 10008（优先括号内的精确值）；`10.008K` → 10008；其余 null。 */
private fun parseRowCount(value: String): Long? {
    Regex("""\((\d+)\)""").find(value)?.let { return it.groupValues[1].toLongOrNull() }
    val match = Regex("""([\d.]+)\s*([KM]?)""").matchEntire(value.trim()) ?: return null
    val number = match.groupValues[1].toDoubleOrNull() ?: return null
    return when (match.groupValues[2]) {
        "K" -> (number * 1_000).toLong()
        "M" -> (number * 1_000_000).toLong()
        else -> number.toLong()
    }
}

private fun parseAnalyzeProfileSummary(raw: String): ProfileSummary {
    val lines = raw.lines()
    // Summary 段在第一个 "Fragment N" 之前：只在这里找键，避免算子树里的
    // TotalTime / OutputRows 行被误当成 Summary 字段。
    val summaryEnd = lines.indexOfFirst { it.trimStart().startsWith("Fragment ") }
    val summaryLines = if (summaryEnd >= 0) lines.subList(0, summaryEnd) else lines

    fun field(key: String): String =
        summaryLines.firstOrNull { it.trimStart().startsWith("$key:") }
            ?.substringAfter(':')?.trim().orEmpty()

    val queryId = field("QueryId")
    val state = field("State")
    val totalTime = field("TotalTime")

    // QueryPeakMemoryUsage: ?, QueryAllocatedMemoryUsage: 1.115 MB → 取非 ? 的那个。
    val peak = summaryLines
        .firstOrNull { it.trimStart().startsWith("QueryPeakMemoryUsage") }
        ?.let { line ->
            val peakValue = line.substringAfter("QueryPeakMemoryUsage:", "").substringBefore(',').trim()
            val allocated = line.substringAfter("QueryAllocatedMemoryUsage:", "").trim()
            when {
                peakValue.isNotEmpty() && peakValue != "?" -> peakValue
                allocated.isNotEmpty() && allocated != "?" -> allocated
                else -> ""
            }
        }
        .orEmpty()

    val topOperators = parseTopOperators(summaryLines)
    val scanRows = parseScanRows(lines)

    return ProfileSummary(queryId, state, totalTime, peak, scanRows, topOperators)
}

/** Top 算子行：`1. OLAP_SCAN (id=0) : 737.656us (58.36%)`（id 与限定词可缺省）。 */
private val TOP_NODE_REGEX = Regex("""^(\d+)\.\s+(.+?)\s*:\s*(.+?)\s*\(([\d.]+)%\)\s*$""")

/** 解析 "Top Most Time-consuming Nodes" 编号列表（FE 已按耗时降序，UI 取前 3）。 */
private fun parseTopOperators(summaryLines: List<String>): List<ProfileOperator> {
    val operators = mutableListOf<ProfileOperator>()
    var inSection = false
    for (line in summaryLines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("Top Most Time-consuming Nodes:")) {
            inSection = true
            continue
        }
        if (!inSection) continue
        if (trimmed.startsWith("Top Most Memory-consuming") || trimmed.startsWith("NonDefaultVariables")) break
        val match = TOP_NODE_REGEX.matchEntire(trimmed) ?: continue
        operators += ProfileOperator(name = match.groupValues[2].trim(), time = match.groupValues[3], pct = "${match.groupValues[4]}%")
    }
    return operators
}

/** 算子树表头行（`└──OLAP_SCAN (id=0)` / `└──RESULT_SINK`）：取树形字符后的算子名。 */
private fun operatorHeaderName(line: String): String? {
    val idx = line.lastIndexOf("──")
    if (idx < 0) return null
    val name = line.substring(idx + "──".length).trim().substringBefore(" (").uppercase()
    return name.takeIf { it.isNotBlank() }
}

/**
 * 扫描行数：算子树中名字含 SCAN 的算子 OutputRows 求和（多 Fragment / 多分区
 * 各自一行时求和近似总扫描行数；OutputRows 为 ? 的算子跳过）。无扫描算子返回空串。
 */
private fun parseScanRows(lines: List<String>): String {
    var lastOperator: String? = null
    var total = 0L
    var hasScan = false
    for (line in lines) {
        val header = operatorHeaderName(line)
        if (header != null) {
            lastOperator = header
            continue
        }
        val trimmed = line.trimStart().trimStart('│').trim()
        if (trimmed.startsWith("OutputRows:") && lastOperator?.contains("SCAN") == true) {
            val rows = trimmed.substringAfter(':').trim().replace(",", "").toLongOrNull()
            if (rows != null) {
                total += rows
                hasScan = true
            }
        }
    }
    return if (hasScan) total.toString() else ""
}

/**
 * Profile 拉取语句（统一入口）：`ANALYZE PROFILE FROM '<uuid>'`（3.1+，**必须
 * 单引号**）。不带引号时 FE 词法器把 UUID 在连字符处切开，报 "No viable
 * statement for input 'ANALYZE PROFILE FROM 297a21b0'" —— 错误消息只显示到
 * 出错 token，看起来像 UUID 被截断成 8 位，实为解析器的报错显示行为。
 *
 * queryId 先做**字符集白名单校验**（UUID 含 `-`）防 SQL 注入；拼接统一收口在
 * 本函数（含单引号转义），调用方不得自行拼 SQL。
 */
internal fun profileStatements(queryId: String): List<String> {
    val safe = queryId.trim()
    require(safe.isNotEmpty() && Regex("""[A-Za-z0-9_:\-]+""").matches(safe)) { "invalid query id" }
    val escaped = safe.replace("'", "''")
    return listOf("ANALYZE PROFILE FROM '$escaped';")
}

/** 会话级开启 Profile 采集的辅助语句（仅当前会话生效，**不动全局配置**）。 */
internal const val ENABLE_PROFILE_SQL = "SET enable_profile = true;"


/** Profile 未采集的业务异常错误键（文案在 ui-common 公共表，userMessage() 渲染）。 */
internal const val KEY_PROFILE_NOT_COLLECTED = "olap.starrocks.profile_not_collected"


/** 业务异常的英文兜底文案（与默认语言一致；日志与缺键回落路径可见）。 */
internal const val PROFILE_NOT_COLLECTED_FALLBACK =
    "Cannot fetch the profile. Please confirm the query state is Finished and the version is >= v3.1."

/**
 * 拉取 Profile 原始文本（Query Profile 闭环的取数入口）：执行
 * [profileStatements] 生成的 `ANALYZE PROFILE FROM '<uuid>'` 并拍平为文本。
 *
 * 报错（版本低于 v3.1 / 权限不足 / 未采集 / 已超保留周期）或空文本时：记录日志
 * 并抛 [ServiceException]（[KEY_PROFILE_NOT_COLLECTED]，userMessage() 渲染指引）。
 * 采集由连接初始化的 `sessionVariables=enable_profile=true` 保证。
 *
 * [runQuery] 可注入以便无环境单测（默认走 [queryRows] → `executeQuery` 流式通道）。
 */
internal suspend fun fetchQueryProfile(
    connectionId: String,
    queryId: String,
    runQuery: suspend (String) -> StarRocksQueryRows = { queryRows(connectionId, it) },
): String {
    val start = System.currentTimeMillis()
    // profileStatements 对非法 queryId 会 require 失败。**必须放在 try 之内**：
    // 否则 IllegalArgumentException("invalid query id") 会绕过下面那个把它映射成
    // 友好文案的 catch，界面直接显示英文内部消息（userMessage() 对非
    // LocalizedException 原样返回 message）。当前调用点恰好都先做过 ID 校验，
    // 但错误出口不该依赖上下游的运气。
    var sql: String? = null
    val text = try {
        sql = profileStatements(queryId).single()
        // 排查「Profile 找不到」的第一现场：确认到底对哪个 QueryId 发了哪条语句。
        StarRocksLog.profile(
            "fetch start connection=$connectionId queryId=$queryId action=ANALYZE_PROFILE " +
                "sql=${StarRocksLog.truncate(sql)}",
        )
        val raw = runQuery(sql)
        StarRocksLog.profile(
            LogLevel.DEBUG,
            "fetch raw queryId=$queryId rows=${raw.rows.size} columns=${raw.columns} " +
                "body=${StarRocksLog.truncate(flattenRows(raw))}",
        )
        stripAnsiEscapes(flattenRows(raw))
    } catch (cancellation: CancellationException) {
        StarRocksLog.profile(
            LogLevel.DEBUG,
            "fetch cancelled queryId=$queryId after ${System.currentTimeMillis() - start}ms",
        )
        throw cancellation
    } catch (error: Throwable) {
        // 具体失败原因（版本不兼容 / 语法错误 / 权限 / 未采集）决定下一步查什么，
        // 所以把 SQLState / ErrorCode 与异常栈一起留下 —— 这是 WARN 也要带栈的场景。
        StarRocksLog.profile(
            LogLevel.WARN,
            "fetch failed queryId=$queryId cost=${System.currentTimeMillis() - start}ms " +
                "errorType=${error::class.simpleName} ${jdbcDiagnostics(error)} " +
                "detail=${error.message} " +
                // sql 为 null 说明失败发生在拼语句之前（非法 queryId）——日志里如实标出来，
                // 比打一个空串更能说明「这条语句根本没发出去」。
                "sql=${sql?.let { StarRocksLog.truncate(it) } ?: "<rejected before build>"}",
            error,
        )
        StarRocksLog.perf(
            action = "fetch_profile",
            type = "PROFILE",
            costMs = System.currentTimeMillis() - start,
        )
        throw ServiceException(
            errorKey = KEY_PROFILE_NOT_COLLECTED,
            fallbackMessage = PROFILE_NOT_COLLECTED_FALLBACK,
            cause = error,
        )
    }
    if (text.isBlank()) {
        // get_query_profile 对未采集查询返回空串（3.4.0 实测）：同样按未采集处理。
        // 空串与「报错」是两种完全不同的根因（未采集 vs 版本不支持），必须分开记。
        StarRocksLog.profile(
            LogLevel.WARN,
            "fetch returned empty text queryId=$queryId cost=${System.currentTimeMillis() - start}ms " +
                "hint=query may be unfinished, unsampled, or evicted from the profile list",
        )
        StarRocksLog.perf(
            action = "fetch_profile",
            type = "PROFILE",
            costMs = System.currentTimeMillis() - start,
        )
        throw ServiceException(
            errorKey = KEY_PROFILE_NOT_COLLECTED,
            fallbackMessage = PROFILE_NOT_COLLECTED_FALLBACK,
        )
    }
    val cost = System.currentTimeMillis() - start
    StarRocksLog.profile("fetch done queryId=$queryId chars=${text.length} cost=${cost}ms")
    StarRocksLog.perf(action = "fetch_profile", type = "PROFILE", costMs = cost)
    return text
}

/**
 * 异常的 JDBC 诊断字段（SQLState / ErrorCode）。
 *
 * Profile 拉取失败最常见的原因是「语句本身不被 FE 接受」（语法 / 版本），
 * SQLState 与 ErrorCode 比异常消息文本稳定得多 —— 消息会随版本改，码不会。
 * 非 JDBC 异常返回空串，日志里不出现空洞字段。
 */
private fun jdbcDiagnostics(error: Throwable): String = when (error) {
    is JdbcFailure -> "sqlState=${error.sqlState ?: "-"} errorCode=${error.errorCode}"
    is JdbcConnectTimeoutException -> "sqlState=- errorCode=- timeout=true"
    else -> ""
}

private fun flattenRows(rows: StarRocksQueryRows): String = rows.rows.joinToString("\n") { row ->
    row.joinToString(" | ") { it?.toString() ?: "NULL" }
}

/**
 * ANSI 终端转义序列清洗：StarRocks 服务端返回的 Profile 文本夹杂颜色控制符
 * （`\u001B[1m` / `\u001B[31m` / `\u001B[0m` 等），在取数层彻底过滤 —— 解析、
 * 展示与「复制全部」拿到的都是纯净文本。
 */
private val ANSI_ESCAPE_REGEX = Regex("\u001B(?:[@-Z\\-_]|\\[[0-?]*[ -/]*[@-~])")

internal fun stripAnsiEscapes(text: String): String = ANSI_ESCAPE_REGEX.replace(text, "")

private val prettyJson = Json { prettyPrint = true }

/**
 * 系统 / 元数据语句判定：`SHOW` / `SET` / `USE` / `EXPLAIN` / `DESC` / `KILL` /
 * 权限类语句不产生 Profile 树 —— 列表「查看 Profile」按钮据此置灰。
 * 取首条非空、非注释行的第一个 token 比对；空语句按系统语句处理（置灰）。
 */
private val SYSTEM_STATEMENT_PREFIXES = setOf("SHOW", "SET", "USE", "EXPLAIN", "DESC", "DESCRIBE", "KILL", "GRANT", "REVOKE")

internal fun isSystemStatement(sql: String): Boolean {
    val firstToken = sql.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("--") && !it.startsWith("#") }
        ?.trimStart('-')
        ?.trim()
        ?.split(Regex("""\s+"""))
        ?.firstOrNull()
        ?.trimStart('(')
        ?.trimEnd(';', ',', ')')
        ?.uppercase()
        .orEmpty()
    return firstToken.isEmpty() || firstToken in SYSTEM_STATEMENT_PREFIXES
}

/** 尝试把一段文本 pretty print 成 JSON；不是合法 JSON 返回 null。 */
private fun prettyJsonOrNull(text: String): String? = runCatching {
    val element: JsonElement = prettyJson.parseToJsonElement(text)
    prettyJson.encodeToString(JsonElement.serializer(), element)
}.getOrNull()

/**
 * Profile 结果 → 展示文本。兼容三种后端返回形态：
 * 1) 整段就是一个 JSON；2) 每行一个 JSON 对象；3) 纯表格文本（原样返回）。
 * 3.4.0 实测为纯文本报告，这里原样透传，保留 JSON 兼容给未来的格式化输出。
 */
internal fun formatProfileText(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return raw
    prettyJsonOrNull(trimmed)?.let { return it }
    val lines = trimmed.lines()
    val prettyLines = lines.map { prettyJsonOrNull(it.trim()) ?: return raw }
    return prettyLines.joinToString("\n")
}

/**
 * Profile AI 联动的载荷：SQL + 摘要指标 + Top 算子 + 原始报告节选。
 * 发送侧统一经 [com.mqclient.aiui.wrapUntrustedContext] 包裹（防注入边界）。
 */
internal fun buildProfileAiPayload(statement: String, summary: ProfileSummary, rawProfile: String): String =
    buildString {
        appendLine("## SQL")
        appendLine(statement.ifBlank { "(unavailable)" })
        appendLine()
        appendLine("## PROFILE SUMMARY")
        appendLine("- state: ${summary.state.ifBlank { "-" }}")
        appendLine("- total time: ${summary.totalTime.ifBlank { "-" }}")
        appendLine("- peak memory: ${summary.peakMemory.ifBlank { "-" }}")
        appendLine("- scan rows: ${summary.scanRows.ifBlank { "-" }}")
        appendLine()
        appendLine("## TOP TIME-CONSUMING OPERATORS")
        if (summary.topOperators.isEmpty()) {
            appendLine("(none parsed)")
        } else {
            summary.topOperators.forEachIndexed { index, op ->
                appendLine("${index + 1}. ${op.name} — ${op.time} (${op.pct})")
            }
        }
        appendLine()
        appendLine("## RAW PROFILE (excerpt)")
        appendLine(rawProfile.take(RAW_PROFILE_EXCERPT_CAP))
    }

private const val RAW_PROFILE_EXCERPT_CAP = 8_000
