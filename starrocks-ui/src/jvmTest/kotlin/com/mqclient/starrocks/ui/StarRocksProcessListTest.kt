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

    @Test
    fun `processlist sql does not reference information_schema`() {
        // StarRocks 的 information_schema 没有 processlist 表（3.4.0 实测
        // "Unknown table 'information_schema.processlist'"），会话列表必须走
        // `SHOW FULL PROCESSLIST` —— 钉住，防止改回 I_S 表查询。
        assertTrue(!PROCESSLIST_SQL.contains("information_schema"), PROCESSLIST_SQL)
        assertTrue(PROCESSLIST_SQL.startsWith("SHOW"), PROCESSLIST_SQL)
    }

    @Test
    fun `parses starrocks 3_4 SHOW FULL PROCESSLIST shape`() {
        // 3.4.0 实测列：Id/User/Host/Db/Command/ConnectionStartTime/Time/State/Info/IsPending。
        // 额外列（ConnectionStartTime/IsPending）被忽略；Db 为 null；无 query_id 列。
        val result = StarRocksQueryRows(
            columns = listOf(
                "Id", "User", "Host", "Db", "Command",
                "ConnectionStartTime", "Time", "State", "Info", "IsPending",
            ),
            rows = listOf(
                listOf(
                    23L, "root", "127.0.0.1:42002", null, "Query",
                    "2026-11-19 11:35:51", 0L, "OK", "SHOW FULL PROCESSLIST", false,
                ),
            ),
        )
        val sessions = parseProcessList(result)
        assertEquals(1, sessions.size)
        with(sessions.single()) {
            assertEquals("23", id)
            assertEquals("root", user)
            assertEquals("127.0.0.1:42002", host)
            assertEquals("-", db, "Db 为 null 时回落占位符")
            assertEquals("Query", command)
            assertEquals("OK", state)
            assertEquals(0L, timeSeconds)
            assertEquals("SHOW FULL PROCESSLIST", info)
            assertEquals("23", queryId, "3.4 无 query_id 列，回落连接 id")
        }
    }

    @Test
    fun `query id column is preferred over connection id`() {
        val result = StarRocksQueryRows(
            columns = columns + "query_id",
            rows = listOf(listOf(42L, "root", "h", "db", "Query", 1L, "Running", "SELECT 1", "a1b2-c3d4")),
        )
        assertEquals("a1b2-c3d4", parseProcessList(result).single().queryId)
        // 没有 query_id 列时回落连接 id，保证按钮在没有真实 StarRocks 时也能点通。
        val fallback = StarRocksQueryRows(
            columns = columns,
            rows = listOf(listOf(42L, "root", "h", "db", "Query", 1L, "Running", "SELECT 1")),
        )
        assertEquals("42", parseProcessList(fallback).single().queryId)
    }

    @Test
    fun `current queries proc maps connection id to real query id`() {
        // 3.4.0 实测列：StartTime/feIp/QueryId/ConnectionId/...（Profile 语句需要 UUID）。
        val result = StarRocksQueryRows(
            columns = listOf(
                "StartTime", "feIp", "QueryId", "ConnectionId", "Database", "User",
                "ScanBytes", "ScanRows", "MemoryUsage", "DiskSpillSize", "CPUTime",
                "ExecTime", "Warehouse", "CustomQueryId", "ResourceGroup",
            ),
            rows = listOf(
                listOf(
                    "2026-11-19 11:41:24", "127.0.0.1", "25ffebe1-e40e-11f1-8289-968dc029c89c",
                    34L, "", "root", "0.000 B", "0 rows", "46.438 KB", "0.000 B",
                    "0.000 s", "2.013 s", "default_warehouse", "", "default_wg",
                ),
            ),
        )
        assertEquals(
            mapOf("34" to "25ffebe1-e40e-11f1-8289-968dc029c89c"),
            parseQueryIdsByConnection(result),
        )
        // 列缺失（老版本 / 形状不符）→ 空 map，会话列表不受影响。
        assertTrue(parseQueryIdsByConnection(StarRocksQueryRows(columns, emptyList())).isEmpty())
        // 非数字 ConnectionId 不进映射（注入防线与 processlist id 同源）。
        val dirty = StarRocksQueryRows(
            columns = listOf("QueryId", "ConnectionId"),
            rows = listOf(listOf("uuid", "34; DROP DATABASE x"), listOf("uuid2", 5L)),
        )
        assertEquals(mapOf("5" to "uuid2"), parseQueryIdsByConnection(dirty))
    }

    @Test
    fun `proc query id map feeds parseProcessList when query_id column is missing`() {
        val result = StarRocksQueryRows(
            columns = columns,
            rows = listOf(listOf(34L, "root", "h", "db", "Query", 2L, "OK", "SELECT SLEEP(2)")),
        )
        val sessions = parseProcessList(
            result,
            mapOf("34" to "25ffebe1-e40e-11f1-8289-968dc029c89c"),
        )
        assertEquals("25ffebe1-e40e-11f1-8289-968dc029c89c", sessions.single().queryId)
        // 无映射时仍回落连接 id（原有行为不变）。
        assertEquals("34", parseProcessList(result).single().queryId)
    }

    @Test
    fun `current queries sql stays a proc statement`() {
        assertTrue(CURRENT_QUERIES_SQL.contains("current_queries"), CURRENT_QUERIES_SQL)
    }

    @Test
    fun `own last query row yields connection id to query id pair`() {
        val result = StarRocksQueryRows(
            columns = listOf("conn_id", "last_qid"),
            rows = listOf(listOf(46L, "4a416c9d-e40e-11f1-8289-968dc029c89c")),
        )
        assertEquals("46" to "4a416c9d-e40e-11f1-8289-968dc029c89c", parseOwnLastQuery(result))
        // 形状不符（缺列 / 空行 / 非数字连接 id）返回 null，load 静默跳过。
        assertNull(parseOwnLastQuery(StarRocksQueryRows(listOf("a", "b"), listOf(listOf(1, 2)))))
        assertNull(parseOwnLastQuery(StarRocksQueryRows(listOf("conn_id", "last_qid"), emptyList())))
        assertNull(
            parseOwnLastQuery(
                StarRocksQueryRows(listOf("conn_id", "last_qid"), listOf(listOf("x", "uuid"))),
            ),
        )
    }

    @Test
    fun `own last query sql selects connection and last query id`() {
        assertTrue(OWN_LAST_QUERY_SQL.contains("LAST_QUERY_ID"), OWN_LAST_QUERY_SQL)
        assertTrue(OWN_LAST_QUERY_SQL.contains("CONNECTION_ID"), OWN_LAST_QUERY_SQL)
    }

    @Test
    fun `profile statements are quoted and fall back to get_query_profile`() {
        val fullUuid = "a1b2-c3d4"
        assertEquals(
            listOf(
                "SELECT get_query_profile('a1b2-c3d4');",
                "ANALYZE PROFILE FROM 'a1b2-c3d4';",
            ),
            profileStatements("a1b2-c3d4"),
        )
        assertEquals(
            listOf(
                "SELECT get_query_profile('42');",
                "ANALYZE PROFILE FROM '42';",
            ),
            profileStatements("42"),
        )
        // 单引号必须：任何语句都不允许出现不带引号的 query id（FE 会把 UUID 在
        // 连字符处切开，报出形如 'ANALYZE PROFILE FROM 297a21b0' 的误导性语法错）。
        assertTrue(
            profileStatements(fullUuid).orEmpty().all { it.contains("'$fullUuid'") },
            "query id 必须单引号包裹",
        )
        assertNull(profileStatements("42; DROP DATABASE x"))
        assertNull(profileStatements("' OR 1=1 --"))
        assertNull(profileStatements(""))
    }

    @Test
    fun `profile text is pretty printed or kept as plain text`() {
        val single = formatProfileText("""{"queryId":"q1","nodes":[{"name":"SCAN","time":12}]}""")
        assertTrue(single.contains("\n") && single.contains("\"queryId\""), "单个 JSON 应缩进：$single")

        val perLine = formatProfileText("""{"a":1}
{"b":2}""")
        assertTrue(perLine.contains("\n\n") || perLine.lines().size >= 4, "每行一个 JSON 也要美化：$perLine")

        val plain = "PLAN FRAGMENT 0\n  SCAN: rows=10"
        assertEquals(plain, formatProfileText(plain), "非 JSON 文本原样返回")
        assertEquals("", formatProfileText(""))
    }
}
