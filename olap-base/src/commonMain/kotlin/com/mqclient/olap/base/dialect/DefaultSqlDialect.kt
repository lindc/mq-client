package com.mqclient.olap.base.dialect

import com.mqclient.olap.base.error.OlapErrorCode

/**
 * ANSI 风格的兜底方言：双引号标识符、单引号字面量、`LIMIT/OFFSET`、`EXPLAIN ` 前缀。
 *
 * 作为「没有更精确方言时的默认值」存在 —— 各协议模块（ClickHouse / Doris / DuckDB…）
 * 应提供自己的 [SqlDialect] 实现，而不是依赖这里的通用行为（深翻页性能、错误分类
 * 精度都取决于方言）。
 */
object DefaultSqlDialect : SqlDialect {

    /** ANSI 标识符转义：双引号包裹，内部双引号翻倍（`a"b` → `"a""b"`）。 */
    override fun quoteIdentifier(identifier: String): String =
        "\"" + identifier.replace("\"", "\"\"") + "\""

    /** ANSI 字符串字面量：单引号包裹，内部单引号翻倍（`a'b` → `'a''b'`）。 */
    override fun stringLiteral(value: String): String =
        "'" + value.replace("'", "''") + "'"

    /** `LIMIT n OFFSET m`（offset 默认 0，0 时省略 OFFSET 段）。 */
    override fun limitOffset(limit: Int, offset: Int): String =
        if (offset > 0) "LIMIT $limit OFFSET $offset" else "LIMIT $limit"

    /** 前缀 `EXPLAIN `；已以 EXPLAIN 开头的查询原样返回（不重复包裹）。 */
    override fun explainPrefix(sql: String): String =
        if (sql.trimStart().startsWith("EXPLAIN", ignoreCase = true)) sql else "EXPLAIN $sql"

    /** `"col" > 'lastValue'`（lastValue 为 null = 首页，返回 null）。 */
    override fun keysetPredicate(column: String, lastValue: String?): String? =
        lastValue?.let { "${quoteIdentifier(column)} > ${stringLiteral(it)}" }

    /** ANSI 层面没有标准只读会话语句 → null（调用方跳过）。 */
    override fun readOnlySessionStatement(): String? = null

    /**
     * 文本兜底分类（协议无关的最后防线；顺序有讲究：先专后泛 —— `query was
     * cancelled` 含 "cancel"、`Permission denied` 含 "denied"，判错顺序会让取消
     * 被记成权限失败）。
     *
     * cause 链遍历带深度上限：成环的异常链（真实世界见过自引用）不能把分类工具
     * 自己拖进死循环 —— 超限按 UNKNOWN 兜底。
     */
    override fun classify(error: Throwable): OlapErrorCode {
        val text = buildString {
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < CAUSE_CHAIN_MAX_DEPTH) {
                append(current.message.orEmpty())
                append(' ')
                current = current.cause
                depth++
            }
        }.lowercase()
        return when {
            "cancel" in text -> OlapErrorCode.CANCELLED
            "timeout" in text || "timed out" in text -> OlapErrorCode.TIMEOUT
            "permission" in text || "denied" in text || "access denied" in text ->
                OlapErrorCode.PERMISSION_DENIED
            "syntax" in text || "parse error" in text -> OlapErrorCode.SYNTAX_ERROR
            "memory" in text || "resource" in text || "too many" in text ->
                OlapErrorCode.RESOURCE_LIMIT
            else -> OlapErrorCode.UNKNOWN
        }
    }

    /** cause 链遍历深度上限（防成环死循环，见 [classify]）。 */
    private const val CAUSE_CHAIN_MAX_DEPTH = 10
}
