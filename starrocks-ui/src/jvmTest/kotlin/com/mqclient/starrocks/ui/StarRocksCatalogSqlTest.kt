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

    @Test
    fun `materialized view query targets the right table type`() {
        assertTrue(MATERIALIZED_VIEW_SQL.contains("table_schema = DATABASE()"))
        assertTrue(MATERIALIZED_VIEW_SQL.contains("table_type = 'MATERIALIZED VIEW'"))
    }

    @Test
    fun `partitions statement escapes identifiers`() {
        assertEquals("SHOW PARTITIONS FROM `orders`;", partitionsSql("orders"))
        assertEquals("SHOW PARTITIONS FROM `we``ird`;", partitionsSql("we`ird"))
    }

    @Test
    fun `partition rows are summarized regardless of column shape`() {
        val partitions = parsePartitions(
            StarRocksQueryRows(
                columns = listOf("PartitionName", "State", "Range"),
                rows = listOf(
                    listOf("p202401", "NORMAL", "[2024-01-01, 2024-02-01)"),
                    listOf(null, null, null),
                    listOf("p202402", "NORMAL", "[2024-02-01, 2024-03-01)"),
                ),
            ),
        )
        assertEquals(2, partitions.size, "全空行应被丢弃")
        assertTrue(partitions[0].summary.contains("p202401"))
        assertTrue(partitions[0].summary.contains("NORMAL"))
        // 空结果集 → 空列表，UI 显示「（无分区）」而非报错。
        assertTrue(parsePartitions(StarRocksQueryRows(emptyList(), emptyList())).isEmpty())
    }
}
