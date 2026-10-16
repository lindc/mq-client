package com.mqclient.topology

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.KuiverViewerState
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 拓扑节点拖动所依赖的 kuiver 拖动生命周期契约（IBM MQ / Kafka 共用）：
 *
 *  - 拖动提交 = [KuiverViewerState.moveNodeBy] 把新位置烘进 layoutedKuiver 并记录进
 *    manualPositions（KEEP_MANUAL 策略下重扫/relayout 后保留）；
 *  - 重置 = [KuiverViewerState.clearManualPositions] 清空手动位置（随后 relayout 恢复
 *    默认布局——relayout 本身由组合驱动，纯 JVM 下只断言 map 语义）；
 *  - 会话内有效 = manualPositions 只活在 viewer state 实例里，换数据（重扫 updateKuiver）
 *    不清除，重建实例即恢复默认。
 *
 * kuiver 升级若改变这些语义，拓扑的「拖动不保存、重置恢复」行为随之变化 —— 本测试先行报错。
 */
class TopologyNodeDragSessionTest {

    private fun node(id: String, x: Float, y: Float) =
        KuiverNode(id, NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp), DpOffset(x.dp, y.dp))

    private fun graph(vararg nodes: KuiverNode) = Kuiver().withNodes(nodes.toList())

    /**
     * KuiverViewerState 的公开构造是 composable 工厂（rememberKuiverViewerState），
     * 主构造 internal —— 纯 JVM 单测经反射构造（internal 只是 Kotlin 编译期检查）。
     * 第三参是打包的 DpOffset（初始 offset），DpOffset.Zero 打包值恒为 0L。
     */
    private fun newState(graph: Kuiver, scale: Float = 1f): KuiverViewerState {
        val ctor = KuiverViewerState::class.java.declaredConstructors
            .first { it.parameterCount == 3 && it.parameterTypes[0] == Kuiver::class.java }
        ctor.isAccessible = true
        return ctor.newInstance(graph, scale, 0L) as KuiverViewerState
    }

    @Test
    fun `拖动提交 - moveNodeBy 把新位置烘进 layoutedKuiver 并记录 manualPositions`() {
        val state = newState(graph(node("LQ1", 100f, 100f)))

        state.moveNodeBy("LQ1", DpOffset(30.dp, 20.dp))

        // 烘进 layoutedKuiver：滚动条的内容包围盒据此实时跟随拖动。
        assertEquals(DpOffset(130.dp, 120.dp), state.layoutedKuiver.nodes["LQ1"]?.position)
        // manualPositions 记录：KEEP_MANUAL 下 relayout 后据此恢复。
        assertEquals(DpOffset(130.dp, 120.dp), state.manualPositions["LQ1"])
    }

    @Test
    fun `未知节点 - moveNodeBy 是 no-op 不炸不记录`() {
        val state = newState(graph(node("LQ1", 100f, 100f)))

        state.moveNodeBy("ghost", DpOffset(30.dp, 20.dp))

        assertTrue(state.manualPositions.isEmpty())
        assertEquals(DpOffset(100.dp, 100.dp), state.layoutedKuiver.nodes["LQ1"]?.position)
    }

    @Test
    fun `原位松手 - 位移为零时 no-op 不产生手动位置`() {
        val state = newState(graph(node("LQ1", 100f, 100f)))

        state.moveNodeBy("LQ1", DpOffset(0.dp, 0.dp))

        assertTrue(state.manualPositions.isEmpty())
    }

    @Test
    fun `重置按钮语义 - clearManualPositions 清空手动位置`() {
        val state = newState(graph(node("LQ1", 0f, 0f), node("LQ2", 200f, 0f)))
        state.moveNodeBy("LQ1", DpOffset(50.dp, 0.dp))
        state.moveNodeBy("LQ2", DpOffset(-50.dp, 0.dp))
        assertEquals(2, state.manualPositions.size)

        state.clearManualPositions()

        assertTrue(state.manualPositions.isEmpty())
    }

    @Test
    fun `拖动补偿 - moveNode 按包围盒中心位移 x scale 反调 offset 视角不动其他节点`() {
        // A(0,0) B(200,0)：位置包围盒中心 (100,0)。A 拖到 60 → 中心移到 (130,0)。
        // kuiver 把中心位移 × scale 补回 offset，保证屏幕上只有被拖节点动。
        val state = newState(graph(node("A", 0f, 0f), node("B", 200f, 0f)), scale = 2f)

        state.moveNode("A", DpOffset(60.dp, 0.dp))

        // (130-100) × 2 = 60。
        assertEquals(DpOffset(60.dp, 0.dp), state.offset)
        // 另一节点不动（同图空间坐标）。
        assertEquals(DpOffset(200.dp, 0.dp), state.layoutedKuiver.nodes["B"]?.position)
    }

    @Test
    fun `会话内有效 - 换数据（重扫 updateKuiver）不清 manualPositions - 重建实例即默认`() {
        val graph = graph(node("LQ1", 0f, 0f))
        val state = newState(graph)
        state.moveNodeBy("LQ1", DpOffset(50.dp, 50.dp))

        // 重扫重建图：TopologyCanvas 走 updateKuiver + relayout，manualPositions 不被清
        //（KEEP_MANUAL 在布局时重新套用）。
        val rescanned = graph(node("LQ1", 0f, 0f), node("LQ2", 200f, 0f))
        state.updateKuiver(rescanned)
        assertEquals(DpOffset(50.dp, 50.dp), state.manualPositions["LQ1"])

        // 关闭/切走 Tab → 组合销毁 → 新 viewer state：无任何手动位置（恢复默认布局）。
        val fresh = newState(rescanned)
        assertNull(fresh.manualPositions["LQ1"])
        assertTrue(fresh.manualPositions.isEmpty())
    }
}
