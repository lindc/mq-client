package com.mqclient.starrocks.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** processlist 解析与 Kill 语句的纯函数守卫（含注入防线）。 */
class StarRocksProcessListTest {

    private val columns = listOf("id", "user", "host", "db", "command", "time", "state", "info")

    @Test
    fun `parses a processlist row by column name`() {
        val result = StarRocksQueryRows(
            columns = columns,
            rows = listOf(
                listOf(42L, "root", "10.0.0.1:1234", "olap_db", "Query", 12L, "Running", "SELECT * FROM t"),
            ),
        )
        val sessions = parseProcessList(result)
        assertEquals(1, sessions.size)
        with(sessions.single()) {
            assertEquals("42", id)
            assertEquals("root", user)
            assertEquals("10.0.0.1:1234", host)
            assertEquals("olap_db", db)
            assertEquals("Running", state)
            assertEquals(12L, timeSeconds)
            assertEquals("SELECT * FROM t", info)
            assertTrue(timeSeconds > SLOW_QUERY_SECONDS, "12s 应判定为慢查询")
        }
    }

    @Test
    fun `column order differences and null info are tolerated`() {
        val result = StarRocksQueryRows(
            columns = listOf("ID", "TIME", "INFO", "USER"),
            rows = listOf(listOf(7L, "3", null, "reader")),
        )
        val sessions = parseProcessList(result)
        assertEquals(1, sessions.size)
        assertEquals("7", sessions[0].id)
        assertEquals(3L, sessions[0].timeSeconds)
        assertEquals("", sessions[0].info)
        assertEquals("reader", sessions[0].user)
        assertEquals("-", sessions[0].host, "缺列用占位，不崩面板")
    }

    @Test
    fun `non numeric id rows are dropped as an injection guard`() {
        val result = StarRocksQueryRows(
            columns = columns,
            rows = listOf(
                listOf("1; DROP DATABASE x", "root", "h", "db", "Query", 1L, "Running", "SELECT 1"),
                listOf(2L, "root", "h", "db", "Query", 1L, "Running", "SELECT 2"),
            ),
        )
        assertEquals(1, parseProcessList(result).size)
    }

    @Test
    fun `kill statements only accept numeric ids and provide fallbacks`() {
        assertEquals(listOf("KILL QUERY 42;", "KILL 42;"), killStatements("42"))
        assertNull(killStatements("42; DROP DATABASE x"))
        assertNull(killStatements("abc"))
    }

    @Test
    fun `empty result yields empty state data`() {
        assertTrue(parseProcessList(StarRocksQueryRows(columns, emptyList())).isEmpty())
    }
}
