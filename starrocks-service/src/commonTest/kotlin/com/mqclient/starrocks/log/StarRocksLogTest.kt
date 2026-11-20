package com.mqclient.starrocks.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 日志门面的**机制**测试：脱敏、截断、语句分类与打点格式。
 *
 * 这些不是「有没有打日志」的测试（那要看调用点），而是「打到日志里的东西
 * 一定安全、且格式稳定」—— 排查时靠 grep `[MQ-Perf][StarRocks]` 与
 * `[StarRocks][Profile]` 找线索，格式漂了就是白干。
 */
class StarRocksLogTest {

    @Test
    fun `tags share the StarRocks prefix and split by link`() {
        assertEquals("[StarRocks][Conn]", StarRocksLog.CONN)
        assertEquals("[StarRocks][SQL]", StarRocksLog.SQL)
        assertEquals("[StarRocks][Meta]", StarRocksLog.META)
        assertEquals("[StarRocks][Profile]", StarRocksLog.PROFILE)
        assertEquals("[MQ-Perf][StarRocks]", StarRocksLog.PERF)
    }

    @Test
    fun `truncate leaves short text untouched`() {
        assertEquals("SELECT 1", StarRocksLog.truncate("SELECT 1"))
    }

    @Test
    fun `truncate cuts at the limit and marks the suffix`() {
        val long = "x".repeat(StarRocksLog.TRUNCATE_LIMIT + 50)
        val cut = StarRocksLog.truncate(long)

        assertTrue(cut.startsWith("x".repeat(StarRocksLog.TRUNCATE_LIMIT)), cut.take(20))
        assertTrue(cut.contains("..."), "截断必须带 ... 后缀：$cut")
        assertTrue(cut.contains("total ${long.length} chars"), "应标出原始长度，便于判断是否需要看完整报告")
    }

    @Test
    fun `truncate collapses newlines so one event stays one log line`() {
        val sql = "SELECT *\n  FROM t\n  WHERE a = 1"
        assertEquals("SELECT * FROM t WHERE a = 1", StarRocksLog.truncate(sql))
    }

    @Test
    fun `password query params are redacted`() {
        val url = "jdbc:mysql://host:9030/db?user=root&password=secret&connectTimeout=3000"
        val safe = StarRocksLog.redactJdbcUrl(url)

        assertFalse(safe.contains("secret"), "密码不得出现在日志里：$safe")
        assertTrue(safe.contains("password=***"), safe)
        assertTrue(safe.contains("connectTimeout=3000"), "非敏感参数应保留：$safe")
    }

    @Test
    fun `credentials embedded in the url userinfo are redacted`() {
        val safe = StarRocksLog.redactJdbcUrl("jdbc:mysql://root:secret@host:9030/db")

        assertFalse(safe.contains("secret"), "密码不得出现在日志里：$safe")
        assertTrue(safe.startsWith("jdbc:mysql://"), safe)
        assertTrue(safe.contains("host:9030/db"), "主机与库名要保留，否则日志失去排查价值：$safe")
    }

    @Test
    fun `password containing an at-sign does not leak its tail`() {
        // userinfo 段按**最后一个** `@` 切分：密码自身可能含 `@`。
        // 旧实现（非贪婪 `[^/@\s]+`）抹掉 `user:p` 就停，输出
        // `jdbc:mysql://***@ss@host:9030/db` —— `ss` 是密码残段，照样泄露。
        //
        // 断言用**精确相等**而不是 contains/endsWith：后者在旧实现下也成立
        // （实测过），守不住这个缺陷。
        assertEquals(
            "jdbc:mysql://***@host:9030/db",
            StarRocksLog.redactJdbcUrl("jdbc:mysql://user:p@ss@host:9030/db"),
        )
    }

    @Test
    fun `at-sign in query parameters is not treated as userinfo`() {
        // `[^/\s]` 在第一个 `/` 处停下，路径 / 参数里的 @ 不会被误当成凭据。
        val url = "jdbc:mysql://host:9030/db?note=a@b"
        assertEquals(url, StarRocksLog.redactJdbcUrl(url))
    }

    @Test
    fun `statement kind separates profile from ordinary show`() {
        assertEquals("PROFILE", StarRocksLog.statementKind("ANALYZE PROFILE FROM 'abc';"))
        assertEquals("PROFILE", StarRocksLog.statementKind("show profilelist;"))
        assertEquals("SELECT", StarRocksLog.statementKind("  select 1"))
        assertEquals("SHOW", StarRocksLog.statementKind("SHOW BACKENDS;"))
        assertEquals("DDL", StarRocksLog.statementKind("CREATE TABLE t (a int)"))
        assertEquals("DML", StarRocksLog.statementKind("delete from t"))
        assertEquals("SESSION", StarRocksLog.statementKind("SET enable_profile = true;"))
        assertEquals("EMPTY", StarRocksLog.statementKind("   "))
    }

    @Test
    fun `perf line matches the MQ-Perf field order`() {
        assertEquals(
            "action=execute_sql, type=PROFILE, cost=124ms",
            StarRocksLog.perfLine("execute_sql", "PROFILE", 124),
        )
    }

    @Test
    fun `sql summary carries type and truncated text`() {
        val summary = StarRocksLog.sqlSummary("SELECT * FROM `orders` LIMIT 100;")
        assertEquals("type=SELECT sql=SELECT * FROM `orders` LIMIT 100;", summary)
    }
}
