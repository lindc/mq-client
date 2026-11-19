package com.mqclient.starrocks.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 元数据 SQL 拼接的注入守卫与解析守卫：表名来自 information_schema（可信度较高），
 * 但仍必须转义，避免带引号的对象名破坏语句或注入额外条件。
 */
class StarRocksCatalogSqlTest {

    @Test
    fun `plain table names produce the expected statements`() {
        assertEquals(
            "SELECT column_name, data_type, is_nullable, column_key, column_default, extra, column_comment " +
                "FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = 'orders';",
            columnsSql("orders"),
        )
        assertEquals("SELECT * FROM `orders` LIMIT 100;", previewSql("orders"))
        assertEquals("SHOW CREATE TABLE `orders`;", ddlSql("orders"))
    }

    @Test
    fun `single quotes in table names are escaped in the literal`() {
        val sql = columnsSql("o'brien")
        assertTrue(sql.contains("table_name = 'o''brien'"), "单引号必须双写：$sql")
    }

    @Test
    fun `backticks in table names are escaped in the identifier`() {
        assertEquals("SHOW CREATE TABLE `we``ird`;", ddlSql("we`ird"))
        val sql = previewSql("we`ird")
        assertTrue(sql.contains("`we``ird`"), "反引号必须双写：$sql")
        assertTrue(sql.endsWith("LIMIT 100;"))
    }

    @Test
    fun `async materialized views are recognized via SHOW MATERIALIZED VIEWS`() {
        // 3.4.0 实测：异步物化视图 table_type = VIEW（不是 MATERIALIZED VIEW）——
        // 必须靠 SHOW MATERIALIZED VIEWS 名单识别，否则会误显示为普通视图。
        assertEquals("SHOW MATERIALIZED VIEWS;", MATERIALIZED_VIEW_SQL)
        val names = parseMaterializedViewNames(
            StarRocksQueryRows(
                columns = listOf("id", "database_name", "name", "refresh_type"),
                rows = listOf(
                    listOf("10213", "test_db", "mv_sales_by_region", "ASYNC"),
                    listOf(null, null, null, null),
                ),
            ),
        )
        assertEquals(setOf("mv_sales_by_region"), names)

        val tables = parseTables(
            StarRocksQueryRows(
                columns = listOf("table_name", "table_comment", "table_type"),
                rows = listOf(
                    listOf("sales", "", "BASE TABLE"),
                    listOf("mv_sales_by_region", "", "VIEW"),
                ),
            ),
            mvNames = names,
        )
        assertTrue(!tables[0].materializedView, "普通表不得误判")
        assertTrue(tables[1].materializedView, "MV 名单里的 VIEW 应识别为物化视图")

        // MySQL / 意外列形状 → 无 MV 名单，table_type 兜底仍可用。
        assertTrue(parseMaterializedViewNames(StarRocksQueryRows(listOf("x"), listOf(listOf("y")))).isEmpty())
    }

    @Test
    fun `partitions statement escapes identifiers`() {
        assertEquals("SHOW PARTITIONS FROM `orders`;", partitionsSql("orders"))
        assertEquals("SHOW PARTITIONS FROM `we``ird`;", partitionsSql("we`ird"))
    }

    @Test
    fun `tables are parsed with type and materialized view flag`() {
        val tables = parseTables(
            StarRocksQueryRows(
                columns = listOf("table_name", "table_comment", "table_type"),
                rows = listOf(
                    listOf("sales", "销售宽表", "BASE TABLE"),
                    listOf("mv_sales_daily", "", "MATERIALIZED VIEW"),
                    listOf(null, null, null),
                ),
            ),
        )
        assertEquals(2, tables.size)
        assertEquals("sales", tables[0].name)
        assertEquals("销售宽表", tables[0].comment)
        assertEquals("BASE TABLE", tables[0].type)
        assertTrue(!tables[0].materializedView)
        assertTrue(tables[1].materializedView)
    }

    @Test
    fun `columns are parsed with full attributes`() {
        val columns = parseColumns(
            StarRocksQueryRows(
                columns = listOf(
                    "column_name", "data_type", "is_nullable", "column_key",
                    "column_default", "extra", "column_comment",
                ),
                rows = listOf(
                    listOf("sale_date", "date", "NO", "DUP", null, "", "销售日期"),
                    listOf("amount", "decimal(12, 2)", "NO", "", "\"0\"", "", ""),
                ),
            ),
        )
        assertEquals(2, columns.size)
        with(columns[0]) {
            assertEquals("sale_date", name)
            assertEquals("date", type)
            assertEquals("NO", nullable)
            assertEquals("DUP", key)
            assertEquals("", default)
            assertEquals("销售日期", comment)
        }
        assertEquals("\"0\"", columns[1].default, "默认值里的双引号原样保留")
    }

    @Test
    fun `partition summary binds the meaningful columns on 3_4`() {
        // 3.4.0 真实输出节选：分区键为空（非 range 分区表），分布键 = region。
        val partitions = parsePartitions(
            StarRocksQueryRows(
                columns = listOf(
                    "PartitionId", "PartitionName", "VisibleVersion", "VisibleVersionTime",
                    "VisibleVersionHash", "State", "PartitionKey", "Range", "DistributionKey",
                    "Buckets", "ReplicationNum", "StorageMedium", "CooldownTime",
                    "LastConsistencyCheckTime", "DataSize", "IsInMemory", "RowCount",
                    "DataVersion", "VersionEpoch", "VersionTxnType",
                ),
                rows = listOf(
                    listOf(
                        "10178", "sales", "4", "2026-11-19 12:47:16", "0", "NORMAL", "", "",
                        "region", "3", "1", "HDD", "9999-12-31 15:59:59", "NULL", "33KB",
                        "false", "10008", "4", "455608065064960000", "TXN_NORMAL",
                    ),
                ),
            ),
        )
        assertEquals(1, partitions.size)
        val summary = partitions.single().summary
        assertTrue(summary.contains("sales"), summary)
        assertTrue(summary.contains("NORMAL"), summary)
        assertTrue(summary.contains("10008 行"), summary)
        assertTrue(summary.contains("33KB"), summary)
        assertTrue(summary.contains("分布: region"), summary)
        assertTrue(!summary.contains("455608065064960000"), "VersionEpoch 等内部列不应出现：$summary")
    }

    @Test
    fun `range partitions keep the range expression`() {
        val partitions = parsePartitions(
            StarRocksQueryRows(
                columns = listOf("PartitionName", "State", "Range", "PartitionKey"),
                rows = listOf(listOf("p202401", "NORMAL", "[2024-01-01, 2024-02-01)", "dt")),
            ),
        )
        assertTrue(partitions.single().summary.contains("[2024-01-01, 2024-02-01)"))
    }

    @Test
    fun `partition rows are summarized regardless of column shape`() {
        // 列名对不上（老版本 / 其他引擎）→ 回落为全列拼接。
        val partitions = parsePartitions(
            StarRocksQueryRows(
                columns = listOf("c1", "c2"),
                rows = listOf(listOf("10178", "sales")),
            ),
        )
        assertEquals("10178 · sales", partitions.single().summary)
        assertTrue(parsePartitions(StarRocksQueryRows(emptyList(), emptyList())).isEmpty())
    }
}
