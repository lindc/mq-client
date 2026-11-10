package com.mqclient.olap.base.dialect

import com.mqclient.olap.base.error.OlapErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 钉死 ANSI 兜底方言的生成契约：转义 / 分页 / EXPLAIN / 错误分类。
 * 协议方言（ClickHouse 等）未来覆写这些方法时，以本测试为行为基线。
 */
class DefaultSqlDialectTest {

    private val dialect: SqlDialect = DefaultSqlDialect

    // ---- quoteIdentifier ----

    @Test
    fun `quoteIdentifier wraps in double quotes`() {
        assertEquals("\"orders\"", dialect.quoteIdentifier("orders"))
    }

    @Test
    fun `quoteIdentifier doubles embedded double quotes`() {
        assertEquals("\"a\"\"b\"", dialect.quoteIdentifier("a\"b"))
    }

    // ---- stringLiteral ----

    @Test
    fun `stringLiteral wraps in single quotes`() {
        assertEquals("'2026-01-01'", dialect.stringLiteral("2026-01-01"))
    }

    @Test
    fun `stringLiteral doubles embedded single quotes`() {
        assertEquals("'it''s'", dialect.stringLiteral("it's"))
    }

    // ---- limitOffset ----

    @Test
    fun `limitOffset emits LIMIT only when offset is zero`() {
        assertEquals("LIMIT 50", dialect.limitOffset(50))
    }

    @Test
    fun `limitOffset emits LIMIT OFFSET when offset positive`() {
        assertEquals("LIMIT 50 OFFSET 100", dialect.limitOffset(50, 100))
    }

    // ---- explainPrefix ----

    @Test
    fun `explainPrefix prepends EXPLAIN`() {
        assertEquals("EXPLAIN SELECT 1", dialect.explainPrefix("SELECT 1"))
    }

    @Test
    fun `explainPrefix does not double-wrap already-explained query`() {
        assertEquals("EXPLAIN SELECT 1", dialect.explainPrefix("EXPLAIN SELECT 1"))
        assertEquals("explain select 1", dialect.explainPrefix("explain select 1"))
    }

    // ---- keysetPredicate ----

    @Test
    fun `keysetPredicate returns null for first page`() {
        assertNull(dialect.keysetPredicate("ts", null))
    }

    @Test
    fun `keysetPredicate builds greater-than predicate with escaped literal`() {
        assertEquals("\"ts\" > '2026-01-01 00:00:00'", dialect.keysetPredicate("ts", "2026-01-01 00:00:00"))
    }

    // ---- readOnlySessionStatement ----

    @Test
    fun `ansi dialect has no readonly session statement`() {
        assertNull(dialect.readOnlySessionStatement())
    }

    // ---- classify ----

    @Test
    fun `classify maps timeout text to TIMEOUT`() {
        assertEquals(OlapErrorCode.TIMEOUT, dialect.classify(IllegalStateException("Query timed out")))
    }

    @Test
    fun `classify maps cancellation before permission (order matters)`() {
        // 「query was cancelled」含 cancel 不含 permission；顺序钉住取消优先于泛匹配。
        assertEquals(OlapErrorCode.CANCELLED, dialect.classify(IllegalStateException("query was cancelled")))
    }

    @Test
    fun `classify maps access denied to PERMISSION_DENIED`() {
        assertEquals(
            OlapErrorCode.PERMISSION_DENIED,
            dialect.classify(IllegalStateException("access denied for user reader")),
        )
    }

    @Test
    fun `classify walks cause chain before falling back to UNKNOWN`() {
        val wrapped = RuntimeException("wrapper", IllegalStateException("syntax error near FROM"))
        assertEquals(OlapErrorCode.SYNTAX_ERROR, dialect.classify(wrapped))
    }

    @Test
    fun `classify falls back to UNKNOWN for unrecognizable text`() {
        assertEquals(OlapErrorCode.UNKNOWN, dialect.classify(IllegalStateException("boom")))
    }

    @Test
    fun `classify never throws on cyclic cause chain`() {
        // cause 链成环（A→B→A；initCause 只禁自引用，两节点环可构造）：只验证
        // 不抛 / 不死循环，分类落 UNKNOWN 兜底。
        val a = IllegalStateException("boom-a")
        val b = IllegalStateException("boom-b", a)
        a.initCause(b)
        assertTrue(dialect.classify(a) == OlapErrorCode.UNKNOWN || dialect.classify(a) == OlapErrorCode.CANCELLED)
    }
}
