package com.mqclient.starrocks.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.mqclient.core.ServiceException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Query Profile 闭环的解析守卫。样例全部来自真实环境
 * （StarRocks allin1-ubuntu:3.4.0）的逐字输出，防止格式假设漂移。
 */
class StarRocksProfileTest {

    /** 3.4.0 实测：`SHOW PROFILELIST` 的列与行（含已结束查询）。 */
    private val profileListColumns =
        listOf("QueryId", "StartTime", "Time", "State", "Statement")

    /** 3.4.0 实测：聚合扫描查询的 ANALYZE PROFILE 报告（节选自真实输出）。 */
    private val aggProfileText = """
        Explain String
        Summary
            QueryId: 7bc5dbcf-e410-11f1-8289-968dc029c89c
            Version: 3.4.0-e94580b
            State: Finished
            TotalTime: 39ms
                ExecutionTime: 10.217ms [Scan: 174.783us (1.71%), Network: 367.969us (3.60%), ResultDeliverTime: 0ns (0.00%), ScheduleTime: 0ns (0.00%)]
                CollectProfileTime: 1ms
                FrontendProfileMergeTime: 9.712ms
            QueryPeakMemoryUsage: ?, QueryAllocatedMemoryUsage: 1.115 MB
            Top Most Time-consuming Nodes:
                1. OLAP_SCAN (id=0) : 737.656us (58.36%)
                2. EXCHANGE (id=3) : 440.092us (34.82%)
                3. AGGREGATION (id=1) [finalize, update]: 54.344us (4.30%)
                4. RESULT_SINK: 20.626us (1.63%)
                5. DECODE (id=2) : 11.832us (0.94%)
            Top Most Memory-consuming Nodes:
            NonDefaultVariables:
                enable_adaptive_sink_dop: false -> true
                enable_profile: false -> true
        Fragment 0
        │   BackendNum: 1
        │   InstancePeakMemoryUsage: 89.530 KB, InstanceAllocatedMemoryUsage: 110.211 KB
        │   PrepareTime: ?
        └──RESULT_SINK
           │   TotalTime: 20.626us (1.63%) [CPUTime: 219.193us]
           │   OutputRows: 3
           │   SinkType: MYSQL_PROTOCAL
           └──EXCHANGE (id=3)
                  Estimates: [row: ?, cpu: ?, memory: ?, network: ?, cost: ?]
                  TotalTime: 440.092us (34.82%) [CPUTime: 72.123us, NetworkTime: 367.969us]
                  OutputRows: 3
                  PeakMemory: ?, AllocatedMemory: ?

        Fragment 1
        └──DATA_STREAM_SINK (id=3)
           │   PartitionType: UNPARTITIONED
           └──DECODE (id=2)
              │   TotalTime: 11.832us (0.94%) [CPUTime: 11.832us]
              │   OutputRows: 3
              └──AGGREGATION (id=1) [finalize, update]
                 │   TotalTime: 54.344us (4.30%) [CPUTime: 54.344us]
                 │   OutputRows: 3
                 │   PeakMemory: ?, AllocatedMemory: ?
                 └──OLAP_SCAN (id=0)
                        Estimates: [row: 8, cpu: 80.00, memory: 0.00, network: 0.00, cost: 40.00]
                        TotalTime: 737.656us (58.36%) [CPUTime: 562.873us, ScanTime: 174.783us]
                        OutputRows: 8
                        Table: : sales
    """.trimIndent()

    @Test
    fun `parses profilelist rows by column name`() {
        val row = listOf<Any?>(
            "1a4f72f6-e410-11f1-8289-968dc029c89c",
            "2026-11-19 11:55:23", "3s16ms", "Finished", "SELECT SLEEP(3)",
        )
        val items = parseProfileList(StarRocksQueryRows(profileListColumns, listOf(row)))
        assertEquals(1, items.size)
        with(items.single()) {
            assertEquals("1a4f72f6-e410-11f1-8289-968dc029c89c", queryId)
            assertEquals("3s16ms", time)
            assertEquals("Finished", state)
            assertEquals("SELECT SLEEP(3)", statement)
        }
        // 无 QueryId 的行被丢弃（列表的基本事实）。
        assertEquals(
            1,
            parseProfileList(
                StarRocksQueryRows(profileListColumns, listOf(row, listOf(null, "t", "1ms", "Finished", "x"))),
            ).size,
        )
    }

    @Test
    fun `parses real profile summary fields`() {
        val summary = parseProfileSummary(aggProfileText)
        assertEquals("7bc5dbcf-e410-11f1-8289-968dc029c89c", summary.queryId)
        assertEquals("Finished", summary.state)
        assertEquals("39ms", summary.totalTime, "Summary 段的 TotalTime（不被算子树的 TotalTime 干扰）")
        assertEquals("1.115 MB", summary.peakMemory, "Peak 为 ? 时回落 QueryAllocatedMemoryUsage")
        assertEquals("8", summary.scanRows, "OLAP_SCAN 的 OutputRows 求和")
    }

    @Test
    fun `parses top time-consuming operators with optional id and qualifiers`() {
        val top = parseProfileSummary(aggProfileText).topOperators
        assertEquals(5, top.size)
        // FE 已按耗时降序；无 id 的 RESULT_SINK 与带限定词的 AGGREGATION 都要能解析。
        assertEquals("OLAP_SCAN (id=0)", top[0].name)
        assertEquals("737.656us", top[0].time)
        assertEquals("58.36%", top[0].pct)
        assertEquals("RESULT_SINK", top[3].name)
        assertEquals("AGGREGATION (id=1) [finalize, update]", top[2].name)
    }

    @Test
    fun `summary is blank-tolerant for garbage input`() {
        val summary = parseProfileSummary("")
        assertEquals("", summary.queryId)
        assertEquals("", summary.totalTime)
        assertEquals("", summary.scanRows)
        assertTrue(summary.topOperators.isEmpty())
    }

    @Test
    fun `ai payload contains sql summary top operators and raw excerpt`() {
        val summary = parseProfileSummary(aggProfileText)
        val payload = buildProfileAiPayload("SELECT region, SUM(amount) FROM sales GROUP BY region", summary, aggProfileText)
        assertTrue(payload.contains("## SQL"), payload)
        assertTrue(payload.contains("total time: 39ms"), payload)
        assertTrue(payload.contains("peak memory: 1.115 MB"), payload)
        assertTrue(payload.contains("scan rows: 8"), payload)
        assertTrue(payload.contains("OLAP_SCAN (id=0) — 737.656us (58.36%)"), payload)
        assertTrue(payload.contains("## RAW PROFILE"), payload)
    }

    @Test
    fun `sql constants stay on the verified statements`() {
        assertEquals("SHOW PROFILELIST;", PROFILELIST_SQL)
        assertTrue(PROFILELIST_SQL.startsWith("SHOW"), PROFILELIST_SQL)
    }

    @Test
    fun `analyze profile keeps the full uuid quoted`() {
        // 回归：FE 词法器把不带引号的 UUID 在连字符处切开，报错里看起来像
        // "ANALYZE PROFILE FROM 297a21b0"（UUID 被截断）—— 语句必须单引号包裹
        // 且携带完整 36 位 UUID。
        val uuid = "297a21b0-e412-11f1-8289-968dc029c89c"
        val statements = profileStatements(uuid).orEmpty()
        assertTrue(statements.isNotEmpty())
        assertTrue(
            statements.all { it.contains("'") && it.contains(uuid) },
            "语句必须含完整 UUID 且单引号包裹：$statements",
        )
        assertTrue(statements.none { it.contains("FROM $uuid;") }, "不得出现不带引号的变体")
    }

    @Test
    fun `not collected maps to the business exception`() = runBlocking {
        // ANALYZE 报错（未采集 / 版本 / 权限）→ 记日志并抛业务异常（i18n 键）。
        val error = runCatching {
            fetchQueryProfile("conn-1", "297a21b0-e412-11f1-8289-968dc029c89c") { _ ->
                throw IllegalStateException("query not exists")
            }
        }.exceptionOrNull()
        assertTrue(error is ServiceException, "应为 ServiceException：$error")
        assertEquals(KEY_PROFILE_NOT_COLLECTED, error.errorKey)
        assertEquals("query not exists", error.cause?.message, "原始错误挂在 cause 上供排查")
    }

    @Test
    fun `successful analyze short-circuits`() = runBlocking {
        val text = fetchQueryProfile("conn-1", "297a21b0-e412-11f1-8289-968dc029c89c") { _ ->
            StarRocksQueryRows(listOf("t"), listOf(listOf("Query:\n  Summary:")))
        }
        assertEquals("Query:\n  Summary:", text)
        Unit
    }

    @Test
    fun `blank result maps to the business exception`() = runBlocking {
        val error = runCatching {
            fetchQueryProfile("conn-1", "297a21b0-e412-11f1-8289-968dc029c89c") { _ ->
                StarRocksQueryRows(listOf("t"), listOf(listOf("")))
            }
        }.exceptionOrNull()
        assertTrue(error is ServiceException, "应为 ServiceException：$error")
        assertEquals(KEY_PROFILE_NOT_COLLECTED, error.errorKey)
        Unit
    }

    @Test
    fun `fetch rejects injection before touching the connection`() = runBlocking {
        val error = runCatching {
            fetchQueryProfile("conn-1", "297a21b0'; DROP DATABASE x") { _ ->
                throw AssertionError("不得执行任何语句")
            }
        }.exceptionOrNull()
        assertEquals("invalid query id", error?.message)
        Unit
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


    @Test
    fun `ansi escapes are stripped from the fetched text`() = runBlocking {
        // 服务端返回的 Profile 文本夹杂 ANSI 颜色控制符 —— 取数层彻底清洗，
        // 展示与「复制全部」拿到的都是纯净文本。
        val colored = "\u001B[1mQuery:\u001B[0m\n  Summary:\n     - Total: \u001B[32m23ms\u001B[0m"
        val text = fetchQueryProfile("conn-1", "297a21b0-e412-11f1-8289-968dc029c89c") { _ ->
            StarRocksQueryRows(listOf("t"), listOf(listOf(colored)))
        }
        assertTrue(!text.contains("\u001B"), "不得残留 ESC 控制符：$text")
        assertTrue(!text.contains("[1m") && !text.contains("[0m") && !text.contains("[32m"), text)
        assertEquals("Query:\n  Summary:\n     - Total: 23ms", text)
    }

    @Test
    fun `strip ansi escapes helper covers csi forms`() {
        assertEquals("abc", stripAnsiEscapes("\u001B[31mabc\u001B[0m"))
        assertEquals("x", stripAnsiEscapes("\u001B[1;32mx\u001B[0m"))
        assertEquals("clean", stripAnsiEscapes("\u001B[1mclean\u001B[0m"))
        assertEquals("plain", stripAnsiEscapes("plain"))
    }

    @Test
    fun `parse summary works on ansi stripped text`() {
        // 清洗先行保证解析不受控制符干扰。
        val colored = "\u001B[1mSummary\u001B[0m\n    QueryId: 605e4f10-e417-11f1-8289-968dc029c89c\n    State: Finished\n    TotalTime: \u001B[32m23ms\u001B[0m"
        val summary = parseProfileSummary(colored)
        assertEquals("605e4f10-e417-11f1-8289-968dc029c89c", summary.queryId)
        assertEquals("Finished", summary.state)
        assertEquals("23ms", summary.totalTime)
    }


    @Test
    fun `viewer color presets keep high contrast in both themes`() {
        // 深色：#1E1E1E 背景配 #D4D4D4 近白文字（用户报告的可读性回归锚点）；
        // 浅色：#F5F5F5 背景配 #333333 文字；两套高亮都可见。
        val dark = viewerColors(dark = true)
        val light = viewerColors(dark = false)
        assertEquals(0x1E1E1E, dark.bg.toArgb().toLong() and 0xFFFFFF)
        assertEquals(0xD4D4D4, dark.text.toArgb().toLong() and 0xFFFFFF)
        assertEquals(0xF5F5F5, light.bg.toArgb().toLong() and 0xFFFFFF)
        assertEquals(0x333333, light.text.toArgb().toLong() and 0xFFFFFF)
        assertTrue(dark.text != dark.bg && light.text != light.bg)
        assertTrue(dark.highlight != light.highlight)
        assertTrue(dark.keyword != Color.White && light.keyword != Color.White)
    }

    @Test
    fun `parses real get_query_profile format`() {
        // 3.4.0 实测：get_query_profile 首选语句返回 `- 键: 值` 缩进风格（与 ANALYZE
        // 报告不同）—— 算子块按 OperatorTotalTime 降序，扫描行数取 SCAN 块 PullRowNum。
        val getProfileText = """
            Query:
              Summary:
                 - Query ID: 605e4f10-e417-11f1-8289-968dc029c89c
                 - Start Time: 2026-11-19 12:47:27
                 - End Time: 2026-11-19 12:47:27
                 - Total: 23ms
                 - Query Type: Query
                 - Query State: Finished
                 - StarRocks Version: 3.4.0-e94580b
                 - User: root
                 - Default Db: test_db
                 - Sql Statement: SELECT region, COUNT(*), ROUND(SUM(amount),2) FROM sales GROUP BY region
                 - Collect Profile Time: 0
                 - IsProfileAsync: true
              Planner:
                 - -- Parser[1] 0
                 - -- Total[1] 8ms
              Execution:
                 - QueryAllocatedMemoryUsage: 1.641 MB
                 - QueryCumulativeScanTime: 223.969us
                 - QueryPeakMemoryUsagePerNode: 824.250 KB
                 - QuerySumMemoryUsage: 824.250 KB
                 - ResultDeliverTime: 0ns
                Fragment 0:
                   - BackendAddresses: 127.0.0.1:9060
                  Pipeline (id=0):
                     - ActiveTime: 54.953us
                    RESULT_SINK (plan_node_id=-1):
                      CommonMetrics:
                         - OperatorTotalTime: 16.466us
                         - PullRowNum: 1
                      UniqueMetrics:
                         - SinkType: MYSQL_PROTOCAL
                  Pipeline (id=1):
                    BUCKET_PROCESS_SINK (plan_node_id=1):
                      CommonMetrics:
                         - OperatorTotalTime: 68.170us
                      UniqueMetrics:
                    OLAP_SCAN (plan_node_id=0):
                      CommonMetrics:
                         - OperatorTotalTime: 456.180us
                         - PullRowNum: 10.008K (10008)
                      UniqueMetrics:
                         - Table: sales
                         - BytesRead: 78.310 KB
                    OLAP_SCAN_PREPARE (plan_node_id=0):
                      CommonMetrics:
                         - OperatorTotalTime: 12.000us
        """.trimIndent()

        val summary = parseProfileSummary(getProfileText)
        assertEquals("605e4f10-e417-11f1-8289-968dc029c89c", summary.queryId)
        assertEquals("Finished", summary.state)
        assertEquals("23ms", summary.totalTime, "- Total: 23ms（不被 Planner 的 -- Total[1] 干扰）")
        assertEquals("824.250 KB", summary.peakMemory, "优先 QueryPeakMemoryUsagePerNode")
        assertEquals("10008", summary.scanRows, "OLAP_SCAN 块 PullRowNum（OLAP_SCAN_PREPARE 不计入）")
        // 按 OperatorTotalTime 降序（与块出现顺序无关）；本格式无占比 → pct 空串。
        assertEquals(
            listOf(
                "OLAP_SCAN" to "456.180us",
                "BUCKET_PROCESS_SINK" to "68.170us",
                "RESULT_SINK" to "16.466us",
            ),
            summary.topOperators.take(3).map { it.name to it.time },
        )
        assertTrue(summary.topOperators.all { it.pct.isEmpty() }, "get_query_profile 格式无占比字段")
    }
}
