package com.mqclient.starrocks.ai

import com.mqclient.starrocks.StarRocksModule

/** 一张表的字段结构（表名 → 列名/类型）。 */
data class StarRocksTableSchema(
    val table: String,
    val columns: List<Pair<String, String>>,
)

/** 一次慢查询诊断的完整上下文（纯数据，不含任何提示词 / 包裹标记）。 */
data class StarRocksAiContext(
    val sql: String,
    val explainPlan: String,
    val tableSchemas: List<StarRocksTableSchema>,
)

/**
 * 「AI 慢查询诊断」上下文构建器（复用 [StarRocksModule.executeQuery] 的流式通道）。
 *
 * 与 MQ 模块的 `*AiChainBuilder` 同款分工：这里只产出**纯数据上下文**，
 * 不可信边界的包裹（`[BEGIN UNTRUSTED CONTEXT]`）由发送侧用 ai-ui 的
 * `wrapUntrustedContext` 统一完成（含伪造标记中和）—— 不在 service 层重复实现
 * 安全边界常量。
 *
 * 每一步都容错：EXPLAIN 不支持 / 表结构查不到都不阻断诊断，只是上下文少一块。
 */
object StarRocksAiChainBuilder {

    /** 从 SQL 中提取 FROM / JOIN 的表名（支持 `db.table` 与反引号），去重保序。 */
    internal fun extractTableNames(sql: String): List<String> {
        val regex = Regex(
            """(?i)\b(?:from|join)\s+`?([A-Za-z0-9_]+)`?(?:\.`?([A-Za-z0-9_]+)`?)?""",
        )
        return regex.findAll(sql)
            .map { m -> m.groupValues[2].takeIf { it.isNotEmpty() } ?: m.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_TABLES)
            .toList()
    }

    /**
     * 组装上下文：执行 `EXPLAIN <SQL>` 拿原始计划文本，再补上涉及表的字段结构。
     * 所有 JDBC 访问都在调用方的 `Dispatchers.IO` 上（[StarRocksModule.executeQuery]
     * 内部再切 IO 协程），不阻塞 UI。
     */
    suspend fun build(connectionId: String, sql: String): StarRocksAiContext {
        val statement = sql.trim().trimEnd(';').trim()
        val plan = runCatching { queryText(connectionId, "EXPLAIN $statement") }
            .getOrDefault("(EXPLAIN unavailable: unsupported or insufficient privilege)")
        val schemas = extractTableNames(statement).mapNotNull { table ->
            runCatching { describeTable(connectionId, table) }.getOrNull()
        }
        return StarRocksAiContext(statement, plan, schemas)
    }

    /**
     * 上下文 → 发给模型的纯数据文本（结构化小节，供 `wrapUntrustedContext` 包裹）。
     * 语言固定为英文小节标题：这是给模型的机器上下文，不参与界面 i18n。
     */
    fun buildPayload(context: StarRocksAiContext): String = buildString {
        appendLine("## SQL")
        appendLine(context.sql)
        appendLine()
        appendLine("## EXPLAIN PLAN")
        appendLine(context.explainPlan.ifBlank { "(unavailable)" })
        appendLine()
        appendLine("## TABLE SCHEMAS")
        if (context.tableSchemas.isEmpty()) {
            appendLine("(no schema information available)")
        } else {
            context.tableSchemas.forEach { schema ->
                appendLine("- ${schema.table}")
                if (schema.columns.isEmpty()) {
                    appendLine("  (no columns resolved)")
                } else {
                    schema.columns.forEach { (name, type) -> appendLine("  $name: $type") }
                }
            }
        }
    }

    /** 把查询结果集拍平成文本（StarRocks EXPLAIN 是单列多行，MySQL 可能是多列表格）。 */
    private suspend fun queryText(connectionId: String, sql: String): String {
        val lines = mutableListOf<String>()
        StarRocksModule.executeQuery(connectionId, sql).collect { batch ->
            batch.rows.forEach { row ->
                lines += row.joinToString(" | ") { it?.toString() ?: "NULL" }
            }
        }
        return lines.joinToString("\n")
    }

    private suspend fun describeTable(connectionId: String, table: String): StarRocksTableSchema {
        val sql = "SELECT column_name, data_type FROM information_schema.columns " +
            "WHERE table_schema = DATABASE() AND table_name = '${table.replace("'", "''")}';"
        val columns = mutableListOf<Pair<String, String>>()
        StarRocksModule.executeQuery(connectionId, sql).collect { batch ->
            batch.rows.forEach { row ->
                val name = row.getOrNull(0)?.toString().orEmpty()
                if (name.isNotBlank()) columns += name to row.getOrNull(1)?.toString().orEmpty()
            }
        }
        return StarRocksTableSchema(table, columns)
    }

    /** 最多补 5 张表的结构，避免一张 SQL 把上下文撑爆。 */
    private const val MAX_TABLES = 5
}
