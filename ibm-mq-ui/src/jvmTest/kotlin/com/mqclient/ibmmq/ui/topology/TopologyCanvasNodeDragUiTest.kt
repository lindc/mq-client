package com.mqclient.ibmmq.ui.topology

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import com.dk.kuiver.model.Kuiver
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MQ 面板 nodeDragEnabled 的 Compose UI 测试（鼠标注入级，不是单元桩）：
 *
 *  - `nodeDragEnabled = true`（TopologyPanel 的实际配置）：按住节点拖动，**只有该节点
 *    动**，其余节点（含 rqmgr: 虚拟节点）原地不动 —— kuiver 原生拖拽 + moveNode 的
 *    中心补偿保证视角不动；
 *  - `nodeDragEnabled = false`：同一手势落在画布平移上，**所有节点同步等量移动**——
 *    「拖节点」语义确实被开关关掉了。
 *
 * 被测组件是 MQ 面板装配的共享画布 [TopologyCanvas]（面板本体与 service 耦合，这里用
 * 同 id 规范 + 同卡片尺寸 + 真实固定列布局 provider 的 MQ 形态数据驱动）。节点经
 * `topology_node:<id>` testTag 定位（TopologyCanvas 在 nodeContent 上打标）。
 */
@OptIn(ExperimentalTestApi::class)
class TopologyCanvasNodeDragUiTest {

    /** MQ 固定列形态：本地队列 → 传输队列 → 通道 → 远程 QMGR（虚拟节点）。 */
    private fun mqData(): TopologyData {
        val ids = listOf("LQ1", "XMIT1", "CH1.SENDER", remoteQmgrNodeId("QM2"))
        val kuiver = Kuiver().withNodes(
            ids.map { KuiverNode(it, NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp)) },
        )
        return TopologyData(
            kuiver = kuiver,
            nodeTypes = mapOf(
                "LQ1" to TopologyNodeType.LOCAL_QUEUE,
                "XMIT1" to TopologyNodeType.TRANSMISSION_QUEUE,
                "CH1.SENDER" to TopologyNodeType.CHANNEL,
                remoteQmgrNodeId("QM2") to TopologyNodeType.REMOTE_QMGR,
            ),
            severities = ids.associateWith { NodeSeverity.NORMAL },
        )
    }

    internal fun ComposeUiTest.setMqCanvas(nodeDragEnabled: Boolean) {
        setContent {
            MaterialTheme {
                TopologyCanvas(
                    data = mqData(),
                    layoutProvider = columnLayoutProvider,
                    onRescan = {},
                    onNodeDoubleClick = {},
                    fallbackType = TopologyNodeType.LOCAL_QUEUE,
                    // 与 TopologyPanel 一致：固定列布局靠模块级类型查找表。
                    onBeforeLayout = { topologyNodeTypes = it.nodeTypes },
                    nodeDragEnabled = nodeDragEnabled,
                )
            }
        }
    }

    // centerOf / dragAt / panIntoView：公共画布手势工具，见 ui-test-kit 模块。

    @Test
    fun `启用拖动 - 按住 LQ1 拖动 - 只有它移动 - 其余节点含 rqmgr 虚拟节点原地`() = runComposeUiTest {
        setMqCanvas(nodeDragEnabled = true)
        waitForIdle() // 首次出图落定

        val lq1Before = panIntoView("topology_node:LQ1")
        val xmitBefore = centerOf("topology_node:XMIT1")
        val rqmgrBefore = centerOf("topology_node:${remoteQmgrNodeId("QM2")}")

        dragAt(lq1Before, 80f, 60f)
        waitForIdle()

        val lq1After = centerOf("topology_node:LQ1")
        val xmitAfter = centerOf("topology_node:XMIT1")
        val rqmgrAfter = centerOf("topology_node:${remoteQmgrNodeId("QM2")}")

        // 被拖节点跟手移动（slop 会吃掉少量位移，按 50/35 下限兜住）。
        assertTrue(lq1After.x - lq1Before.x > 50f, "LQ1 应向右移动: $lq1Before → $lq1After")
        assertTrue(lq1After.y - lq1Before.y > 35f, "LQ1 应向下移动: $lq1Before → $lq1After")
        // 其余节点原地（视角补偿生效；±1px 容差吸收取整误差）。
        assertTrue(abs(xmitAfter.x - xmitBefore.x) < 1f && abs(xmitAfter.y - xmitBefore.y) < 1f,
            "XMIT1 不应移动: $xmitBefore → $xmitAfter")
        assertTrue(abs(rqmgrAfter.x - rqmgrBefore.x) < 1f && abs(rqmgrAfter.y - rqmgrBefore.y) < 1f,
            "rqmgr:QM2 不应移动: $rqmgrBefore → $rqmgrAfter")
    }

    @Test
    fun `禁用拖动 - 同一手势落在画布平移 - 全部节点同步等量移动`() = runComposeUiTest {
        setMqCanvas(nodeDragEnabled = false)
        waitForIdle()

        val lq1Before = panIntoView("topology_node:LQ1")
        val xmitBefore = centerOf("topology_node:XMIT1")

        dragAt(lq1Before, 80f, 60f)
        waitForIdle()

        val lq1After = centerOf("topology_node:LQ1")
        val xmitAfter = centerOf("topology_node:XMIT1")

        // 画布平移：两节点位移一致（≠ 只动被拖节点的拖拽语义）。
        val dx1 = lq1After.x - lq1Before.x
        val dy1 = lq1After.y - lq1Before.y
        val dx2 = xmitAfter.x - xmitBefore.x
        val dy2 = xmitAfter.y - xmitBefore.y
        assertTrue(abs(dx1) > 5f && abs(dy1) > 5f, "画布应整体平移: $lq1Before → $lq1After")
        assertEquals(dx1, dx2, 1f, "XMIT1 与 LQ1 横向位移应一致（整体平移）")
        assertEquals(dy1, dy2, 1f, "XMIT1 与 LQ1 纵向位移应一致（整体平移）")
    }
}
