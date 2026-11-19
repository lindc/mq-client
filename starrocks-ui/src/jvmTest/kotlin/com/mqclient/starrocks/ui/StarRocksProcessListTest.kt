package com.mqclient.starrocks.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「会话与慢查询」（数据源 = `SHOW PROFILELIST`）与 Profile 语句的解析守卫。
 * 样例列名 / 行值均来自真实 StarRocks 3.4.0 输出。
 */
class StarRocksProcessListTest {

    /** 3.4.0 实测：`SHOW PROFILELIST` 的列。 */
    private val profileListColumns =
        listOf("QueryId", "StartTime", "Time", "State", "Statement")

    @Test
    fun `parses profilelist rows by column name`() {
        val result = StarRocksQueryRows(
            columns = profileListColumns,
            rows = listOf(
                listOf(
                    "1a4f72f6-e410-11f1-8289-968dc029c89c",
                    "2026-11-19 11:55:23", "3s16ms", "Finished", "SELECT SLEEP(3)",
                ),
            ),
        )
        val sessions = parseProcessList(result)
        assertEquals(1, sessions.size)
        with(sessions.single()) {
            assertEquals("1a4f72f6-e410-11f1-8289-968dc029c89c", queryId)
            assertEquals("2026-11-19 11:55:23", startTime)
            assertEquals("3s16ms", timeText)
            assertEquals(3L, timeSeconds, "3s16ms → 3 秒（慢查询判定用）")
            assertEquals("Finished", state)
            assertEquals("SELECT SLEEP(3)", statement)
        }
    }

    @Test
    fun `rows without query id are dropped`() {
        val result = StarRocksQueryRows(
            columns = profileListColumns,
            rows = listOf(
                listOf(null, "t", "1ms", "Finished", "x"),
                listOf(
                    "1a4f72f6-e410-11f1-8289-968dc029c89c",
                    "t", "1ms", "Finished", "SELECT 1",
                ),
            ),
        )
        assertEquals(1, parseProcessList(result).size)
    }

    @Test
    fun `empty result yields empty list`() {
        assertTrue(parseProcessList(StarRocksQueryRows(profileListColumns, emptyList())).isEmpty())
    }

    @Test
    fun `profile statements are quoted with the full uuid`() {
        val fullUuid = "297a21b0-e412-11f1-8289-968dc029c89c"
        assertEquals(
            listOf("ANALYZE PROFILE FROM '$fullUuid';"),
            profileStatements(fullUuid),
        )
        // 单引号必须：任何语句都不允许出现不带引号的 query id（FE 会把 UUID 在
        // 连字符处切开，报出形如 'ANALYZE PROFILE FROM 297a21b0' 的误导性语法错）。
        assertTrue(profileStatements(fullUuid).all { it.contains("'$fullUuid'") })
        // 注入输入直接抛出（拒绝拼接），不返回任何语句。
        assertEquals(
            "invalid query id",
            runCatching { profileStatements("42; DROP DATABASE x") }.exceptionOrNull()?.message,
        )
        assertEquals(
            "invalid query id",
            runCatching { profileStatements("' OR 1=1 --") }.exceptionOrNull()?.message,
        )
        assertEquals(
            "invalid query id",
            runCatching { profileStatements("") }.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `system statements are gated from profile collection`() {
        // SHOW / SET / USE / EXPLAIN 等系统与元数据语句不产生 Profile 树 —— 列表按钮防呆。
        listOf(
            "SHOW FULL PROCESSLIST;",
            "SET enable_profile = true;",
            "USE test_db",
            "EXPLAIN SELECT 1",
            "DESC sales",
            "KILL 42;",
        ).forEach { sql -> assertTrue(isSystemStatement(sql), sql) }

        // 普通查询（含行首注释）不是系统语句。
        listOf(
            "SELECT 1",
            "INSERT INTO sales VALUES (NOW(), '华东', 'p', 1.0, 1)",
            "-- 注释\nSELECT 1",
        ).forEach { sql -> assertTrue(!isSystemStatement(sql), sql) }

        // 空语句按系统语句处理（置灰）。
        assertTrue(isSystemStatement(""))
        assertTrue(isSystemStatement("-- 只有注释"))
    }
}
