package com.mqclient.starrocks.system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FE 日志的解析守卫：端口候选、HTML 提取、尾部裁剪。
 *
 * HTML 样例取自 3.4.0 的真实响应（`GET /log?file=fe.log` 的 `<pre>` 段），
 * 只裁短了正文行数 —— 形状（导航栏 + `<pre>` + HTML 转义）保持原样。
 */
class StarRocksFeLogParsingTest {

    // ---------------- 端口候选 ----------------

    @Test
    fun `discovered port wins over configured and default`() {
        assertEquals(
            listOf(8040, 8035, 8030),
            feHttpPortCandidates(discovered = 8040, configured = 8035, queryPort = 9030),
        )
    }

    @Test
    fun `missing sources are skipped and duplicates collapsed`() {
        // 只有 queryPort 时按 -1000 推导。
        assertEquals(listOf(8030), feHttpPortCandidates(null, null, 9030))
        // 推导结果与配置相同 → 只试一次。
        assertEquals(listOf(8030), feHttpPortCandidates(null, 8030, 9030))
        // 全都缺 → 没有候选（上层报 unavailable，而不是拿 0 去连）。
        assertEquals(emptyList(), feHttpPortCandidates(null, null, null))
    }

    @Test
    fun `out of range ports are rejected`() {
        // query_port=500 时 500-1000 是负数；discovered 65_536 也非法。
        assertEquals(emptyList(), feHttpPortCandidates(discovered = 65_536, configured = 0, queryPort = 500))
    }

    // ---------------- HTML 提取 ----------------

    /** 3.4.0 实测响应骨架（导航栏 + `<pre>` 正文）。 */
    private val realShapedResponse = """
        <!DOCTYPE html><html><head><title>StarRocks Cluster</title></head><body>
        <nav class="navbar"><ul><li id="nav_log"><a href="/log">logs</a></li></ul></nav>
        <div class="container"><h2>Log Configuration</h2>
        Level: INFO<br/>Verbose Names: <br/>Audit Names: slow_query,query<br/>
        <h2>Log Contents</h2>Log path is: /data/deploy/starrocks/fe/log/fe.warn.log<br/>
        <pre>2026-11-20 10:30:13.118Z WARN (MASTER 127.0.0.1_9010_1795170612423(1)|1)
        [BDBEnvironment.setupEnvironment():302] failed to setup environment
        2026-11-20 10:33:55.459Z WARN (starrocks-mysql-nio-pool-1|256)
        [ConnectProcessor.handleQuery():389] Failed to parse SQL: DESC TABLE(x), because.
        </pre></div></body></html>
    """.trimIndent()

    @Test
    fun `extract keeps only the pre block`() {
        val text = extractLogText(realShapedResponse)

        assertTrue(text.startsWith("2026-11-20 10:30:13.118Z WARN"), text.take(80))
        assertFalse(text.contains("<nav"), "导航栏不应进入日志正文")
        assertFalse(text.contains("Log path is:"), "页头说明不应进入日志正文")
    }

    @Test
    fun `extract unescapes html entities`() {
        val html = "<html><pre>a &lt; b &amp;&amp; c &gt; d &quot;q&quot; &#39;s&#39;</pre></html>"
        assertEquals("a < b && c > d \"q\" 's'", extractLogText(html))
    }

    @Test
    fun `ampersand decoded last so escaped tags stay literal`() {
        // 日志里会真的出现 "&lt;" 这类字面量（打印 SQL 条件时）。
        // 先解 &amp; 会把 &amp;lt; 变成 &lt; 再被当成标签解成 "<"。
        assertEquals("&lt;", extractLogText("<pre>&amp;lt;</pre>"))
    }

    @Test
    fun `extract falls back to whole body when pre is missing`() {
        // 未来版本若改成纯文本响应，仍然要出内容而不是空白面板。
        assertEquals("plain text log", extractLogText("plain text log"))
    }

    @Test
    fun `extract keeps line breaks`() {
        val text = extractLogText("<pre>line1\nline2\nline3</pre>")
        assertEquals(3, text.lines().size)
    }

    // ---------------- 尾部裁剪 ----------------

    @Test
    fun `tail keeps short logs untouched`() {
        val text = (1..5).joinToString("\n") { "line$it" }
        assertEquals(text, tailLines(text, maxLines = 100))
    }

    @Test
    fun `tail truncates to the newest lines and says how many were dropped`() {
        val text = (1..10).joinToString("\n") { "line$it" }
        val tail = tailLines(text, maxLines = 3)

        val lines = tail.lines()
        assertEquals(4, lines.size, "一行说明 + 3 行日志")
        assertTrue(lines.first().contains("7 earlier lines omitted"), lines.first())
        assertEquals(listOf("line8", "line9", "line10"), lines.drop(1))
    }

    @Test
    fun `tail with zero lines yields empty`() {
        assertEquals("", tailLines("a\nb", maxLines = 0))
    }

    // ---------------- 文件白名单 ----------------

    @Test
    fun `log file names match the fe web page`() {
        assertEquals("fe.log", FeLogFile.LOG.fileName)
        assertEquals("fe.warn.log", FeLogFile.WARN.fileName)
        assertEquals("fe.audit.log", FeLogFile.AUDIT.fileName)
    }
}
