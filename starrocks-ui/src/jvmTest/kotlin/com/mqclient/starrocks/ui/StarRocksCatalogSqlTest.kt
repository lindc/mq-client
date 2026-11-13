package com.mqclient.starrocks.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 元数据 SQL 拼接的注入守卫：表名来自 information_schema（可信度较高），
 * 但仍必须转义，避免带引号的对象名破坏语句或注入额外条件。
 */
class StarRocksCatalogSqlTest {

    @Test
    fun `plain table names produce the expected statements`() {
        assertEquals(
            "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = 'orders';",
            columnsSql("orders"),
        )
        assertEquals("SELECT * FROM `orders` LIMIT 100;", previewSql("orders"))
    }

    @Test
    fun `single quotes in table names are escaped in the literal`() {
        val sql = columnsSql("o'brien")
        assertTrue(sql.contains("table_name = 'o''brien'"), "单引号必须双写：$sql")
    }

    @Test
    fun `backticks in table names are escaped in the identifier`() {
        val sql = previewSql("we`ird")
        assertTrue(sql.contains("`we``ird`"), "反引号必须双写：$sql")
        assertTrue(sql.endsWith("LIMIT 100;"))
    }
}
