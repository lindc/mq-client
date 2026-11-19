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
 * 解析 ANALYZE PROFILE 的纯文本报告。只认 Summary 段的前缀键与算子树行，
 * 形状不符的字段一律空串兜底。
 */
internal fun parseProfileSummary(raw: String): ProfileSummary {
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
 * Profile 拉取语句链（逐条降级尝试，取第一个成功者）：
 *
 * 1. `SELECT get_query_profile('<uuid>')` —— **首选**：不依赖 ANALYZE 的版本支持
 *    （3.4.0 实测可用，含 OperatorTotalTime）；未采集时返回空串（不报错）。
 * 2. `ANALYZE PROFILE FROM '<uuid>'` —— 兜底：3.1+ 支持，**必须单引号**（不带引号时
 *    FE 词法器把 UUID 在连字符处切开，报 "No viable statement for input
 *    'ANALYZE PROFILE FROM 297a21b0'" —— 错误消息只显示到出错 token，看起来像
 *    UUID 被截断成 8 位，实为解析器的报错显示行为）。
 *
 * queryId 先做**字符集白名单校验**（UUID 含 `-`，连接 id 是纯数字）防 SQL 注入；
 * 拼接统一收口在本函数（含单引号转义），调用方不得自行拼 SQL。
 */
internal fun profileStatements(queryId: String): List<String>? {
    val safe = queryId.trim()
    if (safe.isEmpty() || !Regex("""[A-Za-z0-9_:\-]+""").matches(safe)) return null
    val escaped = safe.replace("'", "''")
    return listOf(
        "SELECT get_query_profile('$escaped');",
        "ANALYZE PROFILE FROM '$escaped';",
    )
}

/** 会话级开启 Profile 采集的辅助语句（仅当前会话生效，**不动全局配置**）。 */
internal const val ENABLE_PROFILE_SQL = "SET enable_profile = true;"

/** Profile 未采集的业务异常错误键（文案在 ui-common 公共表，userMessage() 渲染）。 */
internal const val KEY_PROFILE_NOT_COLLECTED = "olap.starrocks.profile_not_collected"

/** 业务异常的英文兜底文案（与默认语言一致；日志与缺键回落路径可见）。 */
private const val PROFILE_NOT_COLLECTED_FALLBACK =
    "The session that executed this query did not enable profile collection, so the profile " +
        "cannot be viewed retroactively. The client enables collection automatically on connect " +
        "(session variable enable_profile) — re-run the query and view its profile after it finishes."

/**
 * 拉取 Profile 原始文本（Query Profile 闭环的取数入口）。
 *
 * 降级链见 [profileStatements]（get_query_profile 首选 / ANALYZE 兜底）；两步都拿
 * 不到非空文本时抛 [ServiceException]（[KEY_PROFILE_NOT_COLLECTED]，userMessage()
 * 渲染指引——客户端已在连接建立时自动开启采集，旧查询无法回溯，请重新发起）。
 * 权限 / 语法类错误保留原样上抛（UI 据此映射版本与权限提示）。
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

    statements.forEachIndexed { index, sql ->
        runCatching { runQuery(sql) }
            .onSuccess { rows ->
                // 空文本 = 该查询没有已收集的 Profile（get_query_profile 对未收集查询
                // 不报错而返回空串，3.4.0 实测）—— 视为未采集，继续降级。
                val text = rows.rows.joinToString("\n") { row ->
                    row.joinToString(" | ") { it?.toString() ?: "NULL" }
                }
                if (text.isNotBlank()) return text
                record(null)
            }
            .onFailure { record(it) }
        // 采集侧兜底已上移到连接初始化（jdbcUrl 的 sessionVariables=enable_profile=true）：
        // 客户端自身发起的查询天然被收集，链内不再重复执行 SET。
    }

    if (notCollected) {
        throw ServiceException(
            errorKey = KEY_PROFILE_NOT_COLLECTED,
            fallbackMessage = PROFILE_NOT_COLLECTED_FALLBACK,
            cause = lastError,
        )
    }
    throw lastError ?: IllegalStateException("profile query failed")
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
