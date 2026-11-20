package com.mqclient.starrocks.cluster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 集群元数据解析的离线单测（不连库）：列名漂移、容量/百分比的两种数值形态、
 * 缺列不崩、以及快照聚合（存活 BE 求和）这几条"最容易静默出错"的契约。
 *
 * 目标版本 StarRocks 3.4；解析器同时兼容 3.0 起的列名变体（见 [normalize]）。
 */
class StarRocksClusterParsersTest {

    // ---------------- 列名 / 数值 ----------------

    @Test
    fun `column lookup tolerates case and underscore drift`() {
        val columns = listOf("BackendId", "ip", "HeartBeatPort", "MEM_USED_PCT", "last_heartbeat")

        assertEquals(0, indexOfAny(columns, "backendId"), "大小写不敏感")
        assertEquals(1, indexOfAny(columns, "IP"), "短名按名命中")
        assertEquals(2, indexOfAny(columns, "heartbeatport"), "驼峰 / 下划线都归一")
        assertEquals(3, indexOfAny(columns, "memusedpct"), "全大写下划线也归一")
        assertNull(indexOfAny(columns, "not_a_column"), "缺列必须返回 null")
        assertNull(indexOfAny(emptyList(), "ip"), "空列集不抛异常")
    }

    @Test
    fun `byte parsing accepts formatted strings and raw bytes`() {
        // SHOW BACKENDS 给的是已格式化字符串（1024 进制）。
        assertEquals(1024L, parseBytes("1 KB"))
        assertEquals(1536L, parseBytes("1.5 KB"))
        assertEquals(536870912L, parseBytes("512MB"), "无空格也接受")
        assertEquals(2147483648L, parseBytes("2 GB"))
        assertEquals(1099511627776L, parseBytes("1 TB"))
        // 小数 GB 在 double 上有尾差：断言落在 1 KB 误差内（不锁死 IL 浮点结果）。
        val fractional = parseBytes("1.234 GB")
        assertTrue(
            fractional != null && kotlin.math.abs(fractional - 1_324_997_410L) < 1024,
            "1.234 GB ≈ 1.234 * 1024^3，实际 $fractional",
        )
        // SHOW PROC '/backends' 给的是裸字节。
        assertEquals(2048L, parseBytes("2048"))
        // 缺失 / 垃圾值一律 null，不抛。
        assertNull(parseBytes(null))
        assertNull(parseBytes("-"))
        assertNull(parseBytes(""))
        assertNull(parseBytes("unknown"))
    }

    @Test
    fun `percent parsing strips the unit and rejects NaN`() {
        assertEquals(0.53, parsePercent("0.53 %"), "StarRocks 的口径：数字 + 空格 + 百分号")
        assertEquals(12.0, parsePercent("12"))
        assertEquals(99.9, parsePercent("99.9%"))
        assertNull(parsePercent("NaN"), "NaN 会让卡片显示 NaN%，必须挡掉")
        assertNull(parsePercent(null))
    }

    // ---------------- FRONTENDS ----------------

    @Test
    fun `frontends map roles ports and alive`() {
        val columns = listOf(
            "Name", "IP", "EditLogPort", "HttpPort", "QueryPort", "RpcPort",
            "Role", "IsMaster", "Alive", "Version", "JoinTime", "LastHeartbeat",
        )
        val rows = listOf(
            listOf("fe-leader", "10.0.0.1", "9010", "8030", "9030", "9020", "LEADER", "true", "true", "3.4.0", "2024-01-01 00:00:00", "2024-05-01 10:00:00"),
            listOf("fe-observer", "10.0.0.2", "9010", "8030", "9030", "9020", "OBSERVER", "false", "true", "3.4.0", "2024-01-02 00:00:00", "2024-05-01 10:00:01"),
        )

        val nodes = parseFrontends(columns, rows)

        assertEquals(2, nodes.size)
        val leader = nodes[0]
        assertEquals(ClusterNodeRole.FE_LEADER, leader.role)
        assertEquals("fe-leader", leader.name)
        assertEquals("fe:fe-leader", leader.nodeId)
        assertEquals("10.0.0.1", leader.ip)
        assertEquals(true, leader.alive)
        assertEquals("3.4.0", leader.version)
        // 端口按文档顺序，且全部进表（属性对话框逐行展示）。
        assertEquals(
            listOf("edit_log_port", "http_port", "query_port", "rpc_port"),
            leader.ports.map { it.label },
        )
        assertEquals("9030", leader.ports.first { it.label == "query_port" }.port)
        assertEquals(ClusterNodeRole.FE_OBSERVER, nodes[1].role)
    }

    @Test
    fun `frontends without role column fall back to follower`() {
        val nodes = parseFrontends(
            columns = listOf("Name", "IP", "Alive"),
            rows = listOf(listOf("fe1", "10.0.0.1", "true")),
        )
        assertEquals(ClusterNodeRole.FE_FOLLOWER, nodes.single().role, "老版本没有 Role 列也不该崩")
        assertEquals(true, nodes.single().alive)
    }

    @Test
    fun `frontend row without any identifier is skipped`() {
        val nodes = parseFrontends(
            columns = listOf("Name", "IP"),
            rows = listOf(listOf(null, null)),
        )
        assertTrue(nodes.isEmpty(), "既无 name 又无 ip 的行直接丢掉，不产生空节点")
    }

    // ---------------- BACKENDS / COMPUTE NODES ----------------

    @Test
    fun `backends parse capacity percent and alive`() {
        val columns = listOf(
            "BackendId", "IP", "HeartbeatPort", "BePort", "HttpPort", "BrpcPort",
            "Alive", "Version", "Status", "CpuCores", "MemUsedPct", "CpuUsedPct",
            "TotalCapacity", "AvailCapacity", "DataUsedCapacity", "UsedPct",
            "TabletNum", "NumRunningQueries", "LastStartTime", "LastHeartbeat",
        )
        val rows = listOf(
            listOf(
                "10001", "10.0.0.11", "9050", "9060", "8040", "8060",
                "true", "3.4.0", "{\"lastSuccessReportTabletsTime\":\"x\"}", "16", "61.5 %", "12.3 %",
                "1.000 TB", "400 GB", "600 GB", "60.0 %",
                "12345", "3", "2024-04-01 00:00:00", "2024-05-01 10:00:00",
            ),
        )

        val node = parseBackends(columns, rows, ClusterNodeRole.BE).single()

        assertEquals(ClusterNodeRole.BE, node.role)
        assertEquals("be:10.0.0.11:9050", node.nodeId, "id 用 ip:heartbeat_port（BackendId 不可靠）")
        assertEquals("10.0.0.11:9050", node.name)
        assertEquals(true, node.alive)
        assertEquals(16, node.cpuCores)
        assertEquals(61.5, node.memUsedPercent)
        assertEquals(12.3, node.cpuUsedPercent)
        assertEquals(1099511627776L, node.diskTotalBytes)
        assertEquals(644245094400L, node.diskUsedBytes)
        assertEquals(60.0, node.diskUsedPercent)
        assertEquals(12345L, node.tabletCount)
        assertEquals(3, node.runningQueries)
        assertEquals(listOf("heartbeat_port", "be_port", "http_port", "brpc_port"), node.ports.map { it.label })
    }

    @Test
    fun `compute nodes are namespaced as cn`() {
        val node = parseBackends(
            columns = listOf("IP", "HeartbeatPort", "Alive", "Version"),
            rows = listOf(listOf("10.0.0.21", "9050", "true", "3.4.0")),
            role = ClusterNodeRole.CN,
        ).single()

        assertEquals("cn:10.0.0.21:9050", node.nodeId)
        assertEquals(ClusterNodeRole.CN, node.role)
        assertNull(node.diskTotalBytes, "CN 没有容量列 → null，不能补 0")
    }

    @Test
    fun `used bytes are derived from total minus avail when data used is missing`() {
        val node = parseBackends(
            columns = listOf("IP", "HeartbeatPort", "TotalCapacity", "AvailCapacity"),
            rows = listOf(listOf("10.0.0.11", "9050", "100 GB", "40 GB")),
            role = ClusterNodeRole.BE,
        ).single()

        assertEquals(60L * 1024 * 1024 * 1024, node.diskUsedBytes)
    }

    // ---------------- CATALOGS / ROUTINE LOADS ----------------

    @Test
    fun `catalogs carry their type in extra`() {
        val catalogs = parseCatalogs(
            columns = listOf("CatalogId", "CatalogType", "Name", "Comment"),
            rows = listOf(
                listOf("1", "hive", "hive0", "hive 集群"),
                listOf("2", "internal", "default_catalog", ""),
            ),
        )

        assertEquals(2, catalogs.size, "default_catalog 也进图（它就是内部目录）")
        assertEquals("catalog:hive0", catalogs[0].nodeId)
        assertEquals(ClusterNodeRole.CATALOG, catalogs[0].role)
        assertEquals("hive", catalogs[0].extra["type"])
        assertTrue(catalogs[0].alive, "目录没有存活概念，恒 true")
        assertTrue(catalogs[0].ports.isEmpty(), "Catalog 没有端口")
    }

    @Test
    fun `routine loads count running only`() {
        val loads = parseRoutineLoads(
            columns = listOf("Id", "Name", "DbName", "TableName", "State", "Lag"),
            rows = listOf(
                listOf("1", "job_running", "db1", "t1", "RUNNING", "0"),
                listOf("2", "job_paused", "db1", "t1", "PAUSED", "120"),
                listOf("3", "job_stopped", "db1", "t2", "STOPPED", null),
            ),
        )

        assertEquals(3, loads.size)
        assertEquals(1, loads.count { it.isRunning })
        assertEquals("120", loads[1].lag)
    }

    // ---------------- PROC 内存合并 ----------------

    @Test
    fun `proc memory merges by ip and heartbeat port`() {
        val backends = parseBackends(
            columns = listOf("IP", "HeartbeatPort", "Alive"),
            rows = listOf(
                listOf("10.0.0.11", "9050", "true"),
                listOf("10.0.0.12", "9050", "true"),
            ),
            role = ClusterNodeRole.BE,
        )
        val merged = applyProcBackendMemory(
            nodes = backends,
            columns = listOf("IP", "HeartbeatPort", "MemLimit", "MemUsed"),
            rows = listOf(
                listOf("10.0.0.11", "9050", "68719476736", "30923764531"),
                listOf("10.0.0.12", "9050", "68719476736", "17179869184"),
            ),
        )

        assertEquals(68719476736L, merged[0].memLimitBytes)
        assertEquals(30923764531L, merged[0].memUsedBytes)
        assertEquals(17179869184L, merged[1].memUsedBytes, "同端口不同 IP 不能串")
    }

    @Test
    fun `proc memory refuses to merge ambiguous single ip`() {
        val backends = parseBackends(
            columns = listOf("IP", "HeartbeatPort"),
            rows = listOf(
                listOf("10.0.0.11", "9050"),
                listOf("10.0.0.11", "9051"),
            ),
            role = ClusterNodeRole.BE,
        )
        // PROC 结果缺心跳端口（只有 IP）：同一 IP 两个实例 → 宁可不合并，也不串数据。
        val merged = applyProcBackendMemory(
            nodes = backends,
            columns = listOf("IP", "MemLimit", "MemUsed"),
            rows = listOf(listOf("10.0.0.11", "68719476736", "1024")),
        )

        assertTrue(merged.all { it.memLimitBytes == null }, "歧义 IP 不应合并内存数据")
    }

    // ---------------- 快照聚合 ----------------

    @Test
    fun `snapshot aggregates only count alive compute nodes`() {
        val nodes = listOf(
            ClusterNodeInfo(
                nodeId = "be:1", role = ClusterNodeRole.BE, name = "1", ip = "10.0.0.1",
                ports = emptyList(), alive = true, version = "3.4.0",
                diskTotalBytes = 1000, diskUsedBytes = 400, memUsedPercent = 50.0,
            ),
            ClusterNodeInfo(
                nodeId = "be:2", role = ClusterNodeRole.BE, name = "2", ip = "10.0.0.2",
                ports = emptyList(), alive = false, version = "3.4.0",
                diskTotalBytes = 2000, diskUsedBytes = 1000, memUsedPercent = 80.0,
            ),
        )
        val snapshot = ClusterSnapshot(nodes = nodes)

        assertEquals(1000L, snapshot.diskTotalBytes, "死节点的容量不该算进集群")
        assertEquals(400L, snapshot.diskUsedBytes)
        assertNull(snapshot.memLimitBytes, "没有 PROC 数据 → null（UI 退化到平均值）")
        assertEquals(65.0, snapshot.memUsedPercentAvg)
        assertEquals(1, snapshot.aliveCount)
        assertEquals("3.4.0", snapshot.version)
        assertEquals(2, snapshot.computeNodes.size)
    }

    @Test
    fun `snapshot version skips placeholder`() {
        val snapshot = ClusterSnapshot(
            nodes = listOf(
                ClusterNodeInfo(
                    nodeId = "catalog:x", role = ClusterNodeRole.CATALOG, name = "x", ip = "",
                    ports = emptyList(), alive = true, version = "-",
                ),
                ClusterNodeInfo(
                    nodeId = "fe:1", role = ClusterNodeRole.FE_LEADER, name = "1", ip = "10.0.0.1",
                    ports = emptyList(), alive = true, version = "3.4.1",
                ),
            ),
        )
        assertEquals("3.4.1", snapshot.version)
        assertNotNull(snapshot.leaderFrontend)
    }
}
