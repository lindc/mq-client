package com.mqclient.starrocks.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 慢查询诊断上下文的纯函数守卫（不触网 / 不依赖数据库）。 */
class StarRocksAiChainBuilderTest {

    @Test
    fun `extracts tables from FROM and JOIN including qualified names and backticks`() {
        val sql = """
            SELECT a.id, b.name
            FROM `olap_db`.orders a
            JOIN users b ON a.user_id = b.id
            LEFT JOIN audit_log l ON l.order_id = a.id
        """.trimIndent()
        assertEquals(listOf("orders", "users", "audit_log"), StarRocksAiChainBuilder.extractTableNames(sql))
    }

    @Test
    fun `table names are de-duplicated and capped`() {
        assertEquals(listOf("t"), StarRocksAiChainBuilder.extractTableNames("SELECT * FROM t JOIN t x ON 1=1"))
        val many = (1..8).joinToString(" ") { "JOIN t$it ON 1=1" }
        assertEquals(5, StarRocksAiChainBuilder.extractTableNames("SELECT * FROM a $many").size)
    }

    @Test
    fun `payload carries SQL explain plan and schemas as plain data`() {
        val context = StarRocksAiContext(
            sql = "SELECT * FROM orders WHERE id = 1",
            explainPlan = "PLAN FRAGMENT 0\n  OUTPUT EXPRS:1",
            tableSchemas = listOf(
                StarRocksTableSchema("orders", listOf("id" to "bigint", "user_id" to "int")),
            ),
        )
        val payload = StarRocksAiChainBuilder.buildPayload(context)
        assertTrue(payload.contains("## SQL") && payload.contains("SELECT * FROM orders"))
        assertTrue(payload.contains("## EXPLAIN PLAN") && payload.contains("PLAN FRAGMENT 0"))
        assertTrue(payload.contains("## TABLE SCHEMAS") && payload.contains("- orders"))
        assertTrue(payload.contains("  id: bigint"))
        // builder 只产出纯数据：不可信边界由发送侧 ai-ui 的 wrapUntrustedContext 统一包裹。
        assertTrue(!payload.contains("[BEGIN UNTRUSTED CONTEXT]"))
    }

    @Test
    fun `missing schema is reported as a placeholder`() {
        val payload = StarRocksAiChainBuilder.buildPayload(
            StarRocksAiContext("SELECT 1", "", emptyList()),
        )
        assertTrue(payload.contains("(unavailable)"))
        assertTrue(payload.contains("(no schema information available)"))
    }
}
