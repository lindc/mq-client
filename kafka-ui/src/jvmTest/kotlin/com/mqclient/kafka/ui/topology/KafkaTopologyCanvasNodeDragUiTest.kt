package com.mqclient.kafka.ui.topology

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import androidx.compose.ui.unit.dp
import com.mqclient.graph.NodeSeverity
import com.mqclient.graph.TopologyData
import com.mqclient.graph.TopologyNodeType
import com.mqclient.topology.CARD_HEIGHT
import com.mqclient.topology.CARD_WIDTH
import com.mqclient.topology.TopologyCanvas
import com.mqclient.uitestkit.centerOf
import com.mqclient.uitestkit.dragAt
import com.mqclient.uitestkit.panIntoView
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Kafka 拓扑 nodeDragEnabled 的 Compose UI 测试（与 ibm-mq-ui 的
 * TopologyCanvasNodeDragUiTest 同构，复用 ui-test-kit 的画布手势工具）：
 *
 *  - KafkaTopologyPanel 的实际配置 `nodeDragEnabled = true`：按住节点拖动，**只有该
 *    节点动**，其余节点原地；
 *  - Kafka 特有的 id 形态覆盖：聚合图 topic 直连 leader broker（t1 / b:0 / b:1）+
 *    展开图带冒号的分区 id（p:t1:0）—— testTag / manualPositions 都以完整 id 为键。
 *
 * 面板本体与 AdminClient 查询耦合，这里用真实 [kafkaColumnLayoutProvider] + 同 id
 * 规范 + 同卡片尺寸的 Kafka 形态数据驱动共享画布 [TopologyCanvas]。
 */
@OptIn(ExperimentalTestApi::class)
class KafkaTopologyCanvasNodeDragUiTest {

    /** [ids] 全部挂同一组卡片尺寸；[edges] 为 from→to 对。 */
    private fun data(
        ids: List<String>,
        types: Map<String, TopologyNodeType>,
        edges: List<Pair<String, String>> = emptyList(),
        badges: Map<String, String> = emptyMap(),
    ): TopologyData = TopologyData(
        kuiver = Kuiver()
            .withNodes(ids.map { KuiverNode(it, NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp)) })
            .withEdges(edges.map { (f, t) -> KuiverEdge(fromId = f, toId = t) }),
        nodeTypes = types,
        severities = ids.associateWith { NodeSeverity.NORMAL },
        badges = badges,
    )

    internal fun ComposeUiTest.setKafkaCanvas(data: TopologyData) {
        setContent {
            MaterialTheme {
                TopologyCanvas(
                    data = data,
                    layoutProvider = kafkaColumnLayoutProvider,
                    onRescan = {},
                    onNodeDoubleClick = {},
                    fallbackType = TopologyNodeType.BROKER,
                    // 与 KafkaTopologyPanel 一致：固定列布局靠模块级类型查找表。
                    onBeforeLayout = { kafkaTopologyNodeTypes = it.nodeTypes },
                    nodeDragEnabled = true,
                )
            }
        }
    }

    @Test
    fun `启用拖动 - 聚合图按住 t1 拖动 - 只有它移动 - leader broker 原地`() = runComposeUiTest {
        setKafkaCanvas(
            data(
                ids = listOf("t1", "b:0", "b:1"),
                types = mapOf(
                    "t1" to TopologyNodeType.TOPIC,
                    "b:0" to TopologyNodeType.BROKER,
                    "b:1" to TopologyNodeType.BROKER,
                ),
                edges = listOf("t1" to "b:0", "t1" to "b:1"),
                badges = mapOf("t1" to "3 分区"),
            ),
        )
        waitForIdle()

        val t1Before = panIntoView("topology_node:t1")
        val b0Before = centerOf("topology_node:b:0")

        dragAt(t1Before, 80f, 60f)
        waitForIdle()

        val t1After = centerOf("topology_node:t1")
        val b0After = centerOf("topology_node:b:0")

        // 被拖节点跟手移动（slop 吃掉少量位移，下限兜住）。
        assertTrue(t1After.x - t1Before.x > 50f, "t1 应向右移动: $t1Before → $t1After")
        assertTrue(t1After.y - t1Before.y > 35f, "t1 应向下移动: $t1Before → $t1After")
        // broker 原地（±1px 容差吸收取整误差）。
        assertTrue(abs(b0After.x - b0Before.x) < 1f && abs(b0After.y - b0Before.y) < 1f,
            "b:0 不应移动: $b0Before → $b0After")
    }

    @Test
    fun `启用拖动 - 展开图带冒号的分区 id 正常定位与拖动`() = runComposeUiTest {
        setKafkaCanvas(
            data(
                ids = listOf("t1", "p:t1:0", "p:t1:1", "b:0", "b:1"),
                types = mapOf(
                    "t1" to TopologyNodeType.TOPIC,
                    "p:t1:0" to TopologyNodeType.PARTITION,
                    "p:t1:1" to TopologyNodeType.PARTITION,
                    "b:0" to TopologyNodeType.BROKER,
                    "b:1" to TopologyNodeType.BROKER,
                ),
                edges = listOf(
                    "t1" to "p:t1:0",
                    "t1" to "p:t1:1",
                    "p:t1:0" to "b:0",
                    "p:t1:1" to "b:1",
                ),
                badges = mapOf("p:t1:0" to "leader 0", "p:t1:1" to "leader 1"),
            ),
        )
        waitForIdle()

        val p0Before = panIntoView("topology_node:p:t1:0")
        val t1Before = centerOf("topology_node:t1")

        dragAt(p0Before, -60f, 60f)
        waitForIdle()

        val p0After = centerOf("topology_node:p:t1:0")
        val t1After = centerOf("topology_node:t1")

        // 冒号 id（p:t1:0）作为 tag / 拖动键无特殊处理，跟手移动。
        assertTrue(p0After.x - p0Before.x < -40f, "p:t1:0 应向左移动: $p0Before → $p0After")
        assertTrue(p0After.y - p0Before.y > 35f, "p:t1:0 应向下移动: $p0Before → $p0After")
        assertTrue(abs(t1After.x - t1Before.x) < 1f && abs(t1After.y - t1Before.y) < 1f,
            "t1 不应移动: $t1Before → $t1After")
    }
}
