package com.mqclient.ibmmq.ui.topology

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.KuiverViewerState
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import com.dk.kuiver.util.calculateNodeBounds
import com.mqclient.topology.CARD_HEIGHT
import com.mqclient.topology.CARD_WIDTH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IBM MQ 拓扑节点拖动的数据层契约：
 *
 *  - 固定列是默认布局，拖动只是会话内覆盖（KuiverViewerState.manualPositions，纯内存）；
 *  - REMOTE_QMGR 虚拟节点 id 带 `rqmgr:` 前缀（[remoteQmgrNodeId]），拖动键是 id 而非
 *    显示名（displayName 只在渲染时剥前缀）——虚拟节点是一等拖动目标；
 *  - 拖动烘进 layoutedKuiver → 滚动条内容包围盒实时跟随（拖出最右节点即可滚到它）；
 *  - 关闭/切走 Tab 重建 viewer state → manualPositions 为空 → 恢复默认布局。
 *
 * builder 的 buildKuiverFromCache 与 service 耦合，这里用同 id 规范 + 同卡片尺寸的
 * 固定列图（与 TopologyGraphBuilder 产出一致的数据形态）直接驱动 viewer state。
 */
class MqTopologyNodeDragTest {

    /** 固定列片段：本地队列 → 传输队列 → 通道 → 远程 QMGR（虚拟）。 */
    private fun mqGraph() = Kuiver().withNodes(
        listOf(
            KuiverNode("LQ1", NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp), DpOffset(0.dp, 0.dp)),
            KuiverNode("XMIT1", NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp), DpOffset(320.dp, 0.dp)),
            KuiverNode("CH1.SENDER", NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp), DpOffset(640.dp, 0.dp)),
            KuiverNode(remoteQmgrNodeId("QM2"), NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp), DpOffset(960.dp, 0.dp)),
        ),
    )

    /**
     * KuiverViewerState 的公开构造是 composable 工厂（rememberKuiverViewerState），
     * 主构造 internal —— 纯 JVM 单测经反射构造（internal 只是 Kotlin 编译期检查）。
     * 第三参是打包的 DpOffset（初始 offset），DpOffset.Zero 打包值恒为 0L。
     */
    private fun newState(graph: Kuiver): KuiverViewerState {
        val ctor = KuiverViewerState::class.java.declaredConstructors
            .first { it.parameterCount == 3 && it.parameterTypes[0] == Kuiver::class.java }
        ctor.isAccessible = true
        return ctor.newInstance(graph, 1f, 0L) as KuiverViewerState
    }

    @Test
    fun `拖动提交 - 普通节点与 rqmgr 虚拟节点都以 id 为键落 manualPositions`() {
        val state = newState(mqGraph())

        state.moveNodeBy("LQ1", DpOffset(10.dp, 40.dp))
        state.moveNodeBy(remoteQmgrNodeId("QM2"), DpOffset(-30.dp, -20.dp))

        assertEquals(DpOffset(10.dp, 40.dp), state.manualPositions["LQ1"])
        assertEquals(DpOffset(930.dp, -20.dp), state.manualPositions[remoteQmgrNodeId("QM2")])
        // 显示名（剥前缀后的 QM2）不进 manualPositions —— 键恒为节点 id。
        assertNull(state.manualPositions["QM2"])
    }

    @Test
    fun `拖动跟随 - 拖出最右节点后内容包围盒向右扩展 - 滚动条可滚到它`() {
        val state = newState(mqGraph())

        val before = state.layoutedKuiver.nodes.values.calculateNodeBounds()
        state.moveNodeBy(remoteQmgrNodeId("QM2"), DpOffset(100.dp, 0.dp))
        val after = state.layoutedKuiver.nodes.values.calculateNodeBounds()

        assertEquals(before.maxX + 100.dp, after.maxX)
        assertEquals(before.maxY, after.maxY)
    }

    @Test
    fun `重置语义 - clearManualPositions 清空后随 relayout 回默认布局`() {
        val state = newState(mqGraph())
        state.moveNodeBy("LQ1", DpOffset(10.dp, 40.dp))
        state.moveNodeBy(remoteQmgrNodeId("QM2"), DpOffset(-30.dp, -20.dp))

        state.clearManualPositions()

        assertTrue(state.manualPositions.isEmpty())
    }

    @Test
    fun `会话恢复 - 重建 viewer state 即默认布局 - 无任何持久化残留`() {
        // 拖动只活在这个实例里……
        val session = newState(mqGraph())
        session.moveNodeBy("LQ1", DpOffset(10.dp, 40.dp))
        session.moveNodeBy(remoteQmgrNodeId("QM2"), DpOffset(-30.dp, -20.dp))
        assertTrue(session.manualPositions.isNotEmpty())

        // ……关 Tab / 切连接 → 组合销毁 → 重建实例：零手动位置，图与默认产出逐节点一致。
        val fresh = newState(mqGraph())
        assertTrue(fresh.manualPositions.isEmpty())
        assertEquals(
            session.kuiver.nodes.mapValues { it.value.position },
            fresh.kuiver.nodes.mapValues { it.value.position },
        )
    }
}
