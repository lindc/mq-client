package com.mqclient.starrocks.ui

import com.dk.kuiver.model.buildKuiver
import com.dk.kuiver.model.layout.LayoutConfig
import com.mqclient.graph.NodeSeverity
import com.mqclient.starrocks.cluster.ClusterNodeInfo
import com.mqclient.starrocks.cluster.ClusterNodeRole
import com.mqclient.starrocks.cluster.ClusterSnapshot
import com.mqclient.starrocks.cluster.RoutineLoadInfo
import com.mqclient.topology.CARD_HEIGHT
import com.mqclient.topology.CARD_WIDTH
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 三列集群拓扑的构建契约（纯函数，不依赖画布 / 数据库）：
 * 分层（Catalog | FE | BE·CN）、连线方向、告警级别、badge、以及两种降级形态
 * （没有 FE 数据 / 空快照）。
 */
class StarRocksTopologyGraphBuilderTest {

    private fun frontend(
        name: String,
        ip: String = "10.0.0.1",
        role: ClusterNodeRole = ClusterNodeRole.FE_LEADER,
        alive: Boolean = true,
        version: String = "3.4.0",
    ) = ClusterNodeInfo(
        nodeId = "fe:$name",
        role = role,
        name = name,
        ip = ip,
        ports = listOf(com.mqclient.starrocks.cluster.ClusterPort("query_port", "9030")),
        alive = alive,
        version = version,
    )

    private fun backend(
        ip: String,
        role: ClusterNodeRole = ClusterNodeRole.BE,
        alive: Boolean = true,
        diskUsedPercent: Double? = 40.0,
        memUsedPercent: Double? = 50.0,
        version: String = "3.4.0",
    ) = ClusterNodeInfo(
        nodeId = (if (role == ClusterNodeRole.CN) "cn:" else "be:") + "$ip:9050",
        role = role,
        name = "$ip:9050",
        ip = ip,
        ports = listOf(com.mqclient.starrocks.cluster.ClusterPort("heartbeat_port", "9050")),
        alive = alive,
        version = version,
        diskUsedPercent = diskUsedPercent,
        memUsedPercent = memUsedPercent,
    )

    private fun catalog(name: String, type: String = "hive") = ClusterNodeInfo(
        nodeId = "catalog:$name",
        role = ClusterNodeRole.CATALOG,
        name = name,
        ip = "",
        ports = emptyList(),
        alive = true,
        version = "-",
        extra = mapOf("type" to type),
    )

    @Test
    fun `snapshot without any node yields an empty graph`() {
        val data = buildStarRocksTopology(ClusterSnapshot(emptyList()), fallbackFeName = "fe-host")

        assertTrue(data.kuiver.nodes.isEmpty())
        assertTrue(data.nodes.isEmpty())
    }

    @Test
    fun `frontend missing falls back to a placeholder leader`() {
        // 只有 BE 数据（普通用户看不到 FRONTENDS 的典型形态）。
        val data = buildStarRocksTopology(
            ClusterSnapshot(nodes = listOf(backend("10.0.0.11"))),
            fallbackFeName = "fe-host",
        )

        val placeholder = data.nodes["fe:fe-host"]
        assertEquals(OlapNodeType.FE_LEADER, placeholder?.type, "接入层必须存在，否则图散架")
        // 占位 FE → BE 仍要连线。
        assertEquals(
            setOf("fe:fe-host" to "be:10.0.0.11:9050"),
            data.kuiver.edges.map { it.fromId to it.toId }.toSet(),
        )
    }

    @Test
    fun `leader connects to follower backends and catalogs`() {
        val snapshot = ClusterSnapshot(
            nodes = listOf(
                frontend("fe-leader"),
                frontend("fe-follower", ip = "10.0.0.2", role = ClusterNodeRole.FE_FOLLOWER),
                frontend("fe-observer", ip = "10.0.0.3", role = ClusterNodeRole.FE_OBSERVER),
                backend("10.0.0.11"),
                backend("10.0.0.12", role = ClusterNodeRole.CN, diskUsedPercent = null),
                catalog("hive0"),
            ),
        )

        val data = buildStarRocksTopology(snapshot, fallbackFeName = "unused")

        assertEquals(6, data.nodes.size)
        assertEquals(OlapNodeType.FE_LEADER, data.nodes["fe:fe-leader"]?.type)
        assertEquals(OlapNodeType.FE_FOLLOWER, data.nodes["fe:fe-follower"]?.type)
        assertEquals(OlapNodeType.FE_OBSERVER, data.nodes["fe:fe-observer"]?.type)
        assertEquals(OlapNodeType.BE, data.nodes["be:10.0.0.11:9050"]?.type)
        assertEquals(OlapNodeType.CN, data.nodes["cn:10.0.0.12:9050"]?.type)
        assertEquals(OlapNodeType.CATALOG, data.nodes["catalog:hive0"]?.type)

        // 所有边都从 leader 出发（元数据复制 / 查询下发 / 目录读取）。
        val edges = data.kuiver.edges.map { it.fromId to it.toId }.toSet()
        assertEquals(
            setOf(
                "fe:fe-leader" to "fe:fe-follower",
                "fe:fe-leader" to "fe:fe-observer",
                "fe:fe-leader" to "be:10.0.0.11:9050",
                "fe:fe-leader" to "cn:10.0.0.12:9050",
                "fe:fe-leader" to "catalog:hive0",
            ),
            edges,
        )
        assertTrue(data.kuiver.edges.all { it.fromId == "fe:fe-leader" }, "不允许 BE→BE / 目录→FE 的反向边")
    }

    @Test
    fun `three columns put catalog left frontends middle compute right`() {
        val snapshot = ClusterSnapshot(
            nodes = listOf(
                frontend("fe-leader"),
                backend("10.0.0.11"),
                catalog("hive0"),
            ),
        )
        val data = buildStarRocksTopology(snapshot, fallbackFeName = "unused")

        // 直接跑布局 provider，按 x 坐标验证分层（Catalog=0 / FE=1 / BE·CN=2）。
        val layouted = starRocksClusterLayout(
            data.kuiver,
            LayoutConfig.Custom(provider = { graph, _ -> graph }),
            data.nodes,
        )

        fun x(nodeId: String): Float = layouted.nodes.getValue(nodeId).position.x.value
        assertTrue(x("catalog:hive0") < x("fe:fe-leader"), "Catalog 必须在最左列")
        assertTrue(x("fe:fe-leader") < x("be:10.0.0.11:9050"), "BE 必须在最右列")
        assertEquals(
            CARD_WIDTH.dp.value,
            layouted.nodes.getValue("fe:fe-leader").dimensions?.width?.value ?: CARD_WIDTH.dp.value,
            "布局不改节点尺寸",
        )
        assertEquals(CARD_HEIGHT.dp.value, data.kuiver.nodes.getValue("fe:fe-leader").dimensions?.height?.value)
    }

    @Test
    fun `dead backend is error and resource thresholds are warning`() {
        val snapshot = ClusterSnapshot(
            nodes = listOf(
                frontend("fe-leader"),
                frontend("fe-dead", ip = "10.0.0.9", alive = false),
                backend("10.0.0.11"),
                backend("10.0.0.12", diskUsedPercent = 92.0),
                backend("10.0.0.13", diskUsedPercent = 10.0, memUsedPercent = 95.0),
                backend("10.0.0.14", alive = false),
            ),
        )

        val data = buildStarRocksTopology(snapshot, fallbackFeName = "unused")

        assertEquals(NodeSeverity.ERROR, data.nodes["fe:fe-dead"]?.severity)
        assertEquals(NodeSeverity.NORMAL, data.nodes["be:10.0.0.11:9050"]?.severity)
        assertEquals(NodeSeverity.WARNING, data.nodes["be:10.0.0.12:9050"]?.severity, "磁盘 ≥85%")
        assertEquals(NodeSeverity.WARNING, data.nodes["be:10.0.0.13:9050"]?.severity, "内存 ≥90%")
        assertEquals(NodeSeverity.ERROR, data.nodes["be:10.0.0.14:9050"]?.severity, "不存活优先于阈值")
    }

    @Test
    fun `badges show role disk and catalog type`() {
        val snapshot = ClusterSnapshot(
            nodes = listOf(
                frontend("fe-leader"),
                frontend("fe-observer", ip = "10.0.0.3", role = ClusterNodeRole.FE_OBSERVER),
                backend("10.0.0.11", diskUsedPercent = 42.7),
                backend("10.0.0.12", role = ClusterNodeRole.CN, diskUsedPercent = null, memUsedPercent = 33.2),
                catalog("iceberg0", type = "iceberg"),
            ),
        )

        val data = buildStarRocksTopology(snapshot, fallbackFeName = "unused")

        assertEquals("LEADER", data.nodes["fe:fe-leader"]?.badge)
        assertEquals("OBSERVER", data.nodes["fe:fe-observer"]?.badge)
        assertEquals("42% 磁盘", data.nodes["be:10.0.0.11:9050"]?.badge)
        assertEquals("33% 内存", data.nodes["cn:10.0.0.12:9050"]?.badge, "CN 没有盘 → 退回内存")
        assertEquals("ICEBERG", data.nodes["catalog:iceberg0"]?.badge)
    }

    @Test
    fun `routine loads do not affect topology but snapshot counts them`() {
        val snapshot = ClusterSnapshot(
            nodes = listOf(frontend("fe-leader")),
            routineLoads = listOf(
                RoutineLoadInfo("1", "job1", "db", "t1", "RUNNING"),
                RoutineLoadInfo("2", "job2", "db", "t1", "PAUSED"),
            ),
        )

        val data = buildStarRocksTopology(snapshot, fallbackFeName = "unused")

        assertEquals(1, data.nodes.size, "Routine Load 不是拓扑节点")
        assertEquals(1, snapshot.runningRoutineLoadCount)
    }
}
