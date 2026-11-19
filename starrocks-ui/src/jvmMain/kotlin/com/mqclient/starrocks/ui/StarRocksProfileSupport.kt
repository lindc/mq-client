package com.mqclient.starrocks.ui

import com.mqclient.core.ServiceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
internal fun parseProfileSummary(raw: String): ProfileSummary =
    if (raw.contains("- Query State:")) parseGetQueryProfileSummary(raw)
    else parseAnalyzeProfileSummary(raw)

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
private fun timeToNanos(text: String): Long =
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
 * Profile 拉取语句链（主查询 + 极端兜底，均经字符集白名单校验 + 单引号转义，
 * 拼接收口在本函数，调用方不得自行拼 SQL）：
 *
 * 1. `ANALYZE PROFILE FROM '<uuid>'` —— **主查询**（3.1+ 富格式报告）。**必须单引号**：
 *    不带引号时 FE 词法器把 UUID 在连字符处切开，报 "No viable statement for input
 *    'ANALYZE PROFILE FROM 297a21b0'" —— 错误消息只显示到出错 token，看起来像
 *    UUID 被截断成 8 位，实为解析器的报错显示行为。
 * 2. `SELECT get_query_profile('<uuid>')` —— **极端兜底**：ANALYZE 因版本 / 权限
 *    不可用时仍能取到 Profile 文本（3.4.0 实测可用）。
 */
internal fun profileStatements(queryId: String): List<String>? {
    val safe = queryId.trim()
    if (safe.isEmpty() || !Regex("""[A-Za-z0-9_:\-]+""").matches(safe)) return null
    val escaped = safe.replace("'", "''")
    return listOf(
        "ANALYZE PROFILE FROM '$escaped';",
        "SELECT get_query_profile('$escaped');",
    )
}

/** 会话级开启 Profile 采集的辅助语句（仅当前会话生效，**不动全局配置**）。 */
internal const val ENABLE_PROFILE_SQL = "SET enable_profile = true;"

/** 本会话最近一次查询的 Query ID（SQL 控制台「查看本次 Profile」入口用）。 */
internal const val LAST_QUERY_ID_SQL = "SELECT LAST_QUERY_ID() AS last_qid;"

/** Profile 未采集的业务异常错误键（文案在 ui-common 公共表，userMessage() 渲染）。 */
internal const val KEY_PROFILE_NOT_COLLECTED = "olap.starrocks.profile_not_collected"

/** 记录存在但拿不到详情的业务异常错误键（版本 / 权限限制）。 */
internal const val KEY_PROFILE_DETAIL_UNSUPPORTED = "olap.starrocks.profile_detail_unsupported"

/** 业务异常的英文兜底文案（与默认语言一致；日志与缺键回落路径可见）。 */
private const val PROFILE_NOT_COLLECTED_FALLBACK =
    "Cannot fetch the profile for this query. Possible causes: the cluster version is below v3.1, " +
        "or the query has exceeded the FE profile retention period."

private const val PROFILE_DETAIL_UNSUPPORTED_FALLBACK =
    "The query record exists in the profile list, but this StarRocks version does not support " +
        "fetching the profile detail."

/**
 * 拉取 Profile 原始文本（Query Profile 闭环的取数入口）。
 *
 * 降级链（**不依赖任何 SET** —— 采集由连接初始化的
 * `sessionVariables=enable_profile=true` 保证）：
 * 1. `ANALYZE PROFILE FROM '<uuid>'` 主查询（3.1+ 富格式报告）；
 * 2. 失败后经 `SHOW PROFILELIST` 确认 QueryId 是否有记录（区分「未采集 / 超出
 *    保留周期」与「版本不支持获取详情」）；
 * 3. `SELECT get_query_profile('<uuid>')` 极端兜底。
 *
 * 全部落空时抛 [ServiceException]：
 * - 记录不存在 / 空结果 → [KEY_PROFILE_NOT_COLLECTED]；
 * - 记录存在但拿不到详情 → [KEY_PROFILE_DETAIL_UNSUPPORTED]；
 * - 权限 / 语法类错误保留原样上抛（UI 映射版本与权限提示）。
 *
 * [runQuery] 可注入以便无环境单测（默认走 [queryRows] → `executeQuery` 流式通道）。
 */
internal suspend fun fetchQueryProfile(
    connectionId: String,
    queryId: String,
    runQuery: suspend (String) -> StarRocksQueryRows = { queryRows(connectionId, it) },
): String {
    val statements = profileStatements(queryId)
        ?: throw IllegalArgumentException("invalid query id")
    var lastError: Throwable? = null
    var notCollected = false
    var existsInProfileList = false

    // 归类单条语句的失败：Profile 未采集类（空结果 / "query not exists" / "not found"）
    // → 业务异常；其余（权限 / 语法）保留原异常，UI 映射版本与权限提示。
    fun record(error: Throwable?) {
        val raw = error?.message.orEmpty().lowercase()
        if (error == null || "query not exists" in raw || "not found" in raw) {
            notCollected = true
        } else {
            lastError = error
        }
    }

    fun flatten(rows: StarRocksQueryRows): String = rows.rows.joinToString("\n") { row ->
        row.joinToString(" | ") { it?.toString() ?: "NULL" }
    }

    // 1. 主查询：ANALYZE PROFILE（富格式报告）。
    runCatching { runQuery(statements[0]) }
        .onSuccess { rows ->
            val text = flatten(rows)
            if (text.isNotBlank()) return text
            record(null)   // 空文本 = Profile 未采集 / 已过保留周期
        }
        .onFailure { record(it) }

    // 2. 降级确认：SHOW PROFILELIST 里该 QueryId 是否有记录（失败不阻断）。
    runCatching {
        existsInProfileList = parseProfileList(runQuery(PROFILELIST_SQL))
            .any { it.queryId.equals(queryId.trim(), ignoreCase = true) }
    }

    // 3. 极端兜底：get_query_profile。
    runCatching { runQuery(statements[1]) }
        .onSuccess { rows ->
            val text = flatten(rows)
            if (text.isNotBlank()) return text
            record(null)
        }
        .onFailure { record(it) }

    throw when {
        existsInProfileList -> ServiceException(
            errorKey = KEY_PROFILE_DETAIL_UNSUPPORTED,
            fallbackMessage = PROFILE_DETAIL_UNSUPPORTED_FALLBACK,
            cause = lastError,
        )

        notCollected -> ServiceException(
            errorKey = KEY_PROFILE_NOT_COLLECTED,
            fallbackMessage = PROFILE_NOT_COLLECTED_FALLBACK,
            cause = lastError,
        )

        else -> lastError ?: IllegalStateException("profile query failed")
    }
}

private val prettyJson = Json { prettyPrint = true }

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
