package com.mqclient.topology.ui

import com.mqclient.graph.topology.TopologyEdge
import com.mqclient.graph.topology.TopologyGraph
import com.mqclient.graph.topology.TopologyNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * TopologyGraph → kuiver 映射的契约测试：
 * 保证「通用图里的边」在 kuiver 模型中不丢（布局为 kuiver internal，只能 UI 侧验证）。
 */
class TopologyGraphMappingTest {

    private fun twoLayerGraph() = TopologyGraph(
        connectionId = "c1",
        nodes = listOf(
            TopologyNode(name = "ALIAS.A", type = "alias_queue"),
            TopologyNode(name = "LOCAL.Q", type = "queue"),
        ),
        edges = listOf(TopologyEdge(from = "ALIAS.A", to = "LOCAL.Q")),
    )

    @Test
    fun `edges survive the kuiver mapping`() {
        val k = twoLayerGraph().toKuiver()
        assertEquals(2, k.nodes.size)
        assertEquals(1, k.edges.size, "通用图中的边必须原样进入 kuiver 模型")
        assertEquals("ALIAS.A", k.edges.first().fromId)
        assertEquals("LOCAL.Q", k.edges.first().toId)
    }
}
