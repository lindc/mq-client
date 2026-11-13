package com.mqclient.starrocks.ui

import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyNodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FE→BE 星形拓扑构建契约（纯函数，不依赖画布 / 数据库）。 */
class StarRocksTopologyGraphBuilderTest {

    @Test
    fun `no backends produces an empty graph for the empty state`() {
        val data = buildStarRocksTopology("fe-host", emptyList())
        assertTrue(data.kuiver.nodes.isEmpty())
        assertTrue(data.kuiver.edges.isEmpty())
        assertTrue(data.nodeTypes.isEmpty())
    }

    @Test
    fun `frontend is the center and each backend gets a node and an edge`() {
        val backends = listOf(
            ClusterNode(ip = "10.0.0.1", port = "9050", alive = true, version = "3.2.0"),
            ClusterNode(ip = "10.0.0.2", port = "9050", alive = false, version = "3.2.0"),
        )
        val data = buildStarRocksTopology("fe-host", backends)

        val feId = feNodeId("fe-host")
        val be1 = beNodeId("10.0.0.1", "9050")
        val be2 = beNodeId("10.0.0.2", "9050")

        // 1 FE + 2 BE
        assertEquals(3, data.kuiver.nodes.size)
        assertEquals(TopologyNodeType.OLAP_FE, data.nodeTypes[feId])
        assertEquals(TopologyNodeType.OLAP_BE, data.nodeTypes[be1])
        assertEquals(TopologyNodeType.OLAP_BE, data.nodeTypes[be2])

        // 所有边都从 FE 出发（星形），无 BE→BE 边。
        assertEquals(2, data.kuiver.edges.size)
        assertTrue(data.kuiver.edges.all { it.fromId == feId })
        assertEquals(setOf(be1, be2), data.kuiver.edges.map { it.toId }.toSet())

        // 不存活 → ERROR（红），存活 → NORMAL。
        assertEquals(NodeSeverity.NORMAL, data.severities[feId])
        assertEquals(NodeSeverity.NORMAL, data.severities[be1])
        assertEquals(NodeSeverity.ERROR, data.severities[be2])
        assertEquals("3.2.0", data.badges[be1])
    }

    @Test
    fun `node ids are namespaced so FE name cannot collide with a backend`() {
        val data = buildStarRocksTopology(
            "10.0.0.1",
            listOf(ClusterNode("10.0.0.1", "9050", alive = true, version = "-")),
        )
        assertEquals(2, data.kuiver.nodes.size, "FE 与同 IP 的 BE 必须是两个节点")
        assertTrue(data.badges.isEmpty(), "版本占位 '-' 不应进 badge")
    }
}
