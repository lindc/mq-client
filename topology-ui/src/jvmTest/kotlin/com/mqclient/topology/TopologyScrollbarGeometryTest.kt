package com.mqclient.topology

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.KuiverViewerState
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 滚动条 thumb 拖拽的坐标换算（[dragWindow] / [windowStart]）：拖 thumb 与内容屏幕位
 * 移 1:1（graphicsLayer translation 在缩放之外），换算只除 scale 一次；窗口起点夹在
 * 内容范围内（含卡片尺寸的 bounds），scale 与另一轴 offset 原样保留。
 *
 * 图形固定用两张 MQ 卡片（180×56，中心距 200）：内容 bounds x ∈ [−90, 290]，
 * 位置中心 (100, 0) —— 与 IBM MQ 拓扑节点同尺寸。
 */
class TopologyScrollbarGeometryTest {

    private fun graph() = Kuiver().withNodes(
        listOf(
            KuiverNode("A", NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp), DpOffset(0.dp, 0.dp)),
            KuiverNode("B", NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp), DpOffset(200.dp, 0.dp)),
        ),
    )

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
    fun `windowStart - 无偏移时窗口起点 = 图中心 - 半个视口`() {
        // 视口 300，scale 1：起点 = 100 − 150 = −50。
        assertEquals(-50f, windowStart(canvas = 300f, graphCenter = 100f, offset = 0f, scale = 1f))
        // scale 2：图空间视口减半。
        assertEquals(25f, windowStart(canvas = 300f, graphCenter = 100f, offset = 0f, scale = 2f))
    }

    @Test
    fun `thumb 拖拽 - 窗口起点前移 delta 除 scale - offset 与 scale 另一轴往返一致`() {
        val state = newState(graph(), scale = 1f)
        state.updateTransform(1f, DpOffset(40.dp, 7.dp))

        dragWindow(state, startVisStart = -90f, delta = 40f, scale = 1f, canvas = 300f, fixedOther = 7.dp, horizontal = true)

        // 新窗口起点 −50：与 offset/roundtrip 相互印证（公式互逆）。
        assertEquals(-50f, windowStart(300f, 100f, state.offset.x.value, state.scale))
        // scale 与另一轴原样带回。
        assertEquals(1f, state.scale)
        assertEquals(7.dp, state.offset.y)
    }

    @Test
    fun `thumb 拖拽 - scale 2 时窗口位移折半 - 验证缩放换算`() {
        val state = newState(graph(), scale = 2f)

        // visLen = 300/2 = 150；delta 40（thumb 屏幕位移）→ 窗口前移 20（图 dp）。
        dragWindow(state, startVisStart = -90f, delta = 40f, scale = 2f, canvas = 300f, fixedOther = 0.dp, horizontal = true)

        assertEquals(-70f, windowStart(300f, 100f, state.offset.x.value, state.scale), 1e-4f)
        assertEquals(2f, state.scale)
    }

    @Test
    fun `thumb 拖拽 - 超出内容范围时夹在 bounds 边缘`() {
        val state = newState(graph(), scale = 1f)

        // 右拖 1000：窗口起点夹在 maxX − visLen = 290 − 300 = −10。
        dragWindow(state, startVisStart = -90f, delta = 1000f, scale = 1f, canvas = 300f, fixedOther = 0.dp, horizontal = true)
        assertEquals(-10f, windowStart(300f, 100f, state.offset.x.value, state.scale), 1e-4f)

        // 左拖 1000：夹在 minX = −90。
        dragWindow(state, startVisStart = -10f, delta = -1000f, scale = 1f, canvas = 300f, fixedOther = 0.dp, horizontal = true)
        assertEquals(-90f, windowStart(300f, 100f, state.offset.x.value, state.scale), 1e-4f)
    }

    @Test
    fun `垂直 thumb - 换算走 Y 轴夹 bounds 纵向边缘`() {
        val graph = Kuiver().withNodes(
            listOf(
                KuiverNode("A", NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp), DpOffset(0.dp, 0.dp)),
                KuiverNode("B", NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp), DpOffset(0.dp, 200.dp)),
            ),
        )
        val state = newState(graph)

        // 纵向内容 y ∈ [−28, 228]（卡片高 56 半高 28），视口 200 → maxStart = 228 − 200 = 28。
        dragWindow(state, startVisStart = -28f, delta = 1000f, scale = 1f, canvas = 200f, fixedOther = 0.dp, horizontal = false)

        assertEquals(28f, windowStart(200f, 100f, state.offset.y.value, state.scale), 1e-4f)
        // 横轴原样保留。
        assertEquals(0.dp, state.offset.x)
    }

    @Test
    fun `空图 - no-op 不炸`() {
        val state = newState(Kuiver())
        state.updateTransform(1f, DpOffset(12.dp, 34.dp))

        dragWindow(state, startVisStart = 0f, delta = 40f, scale = 1f, canvas = 300f, fixedOther = 34.dp, horizontal = true)

        assertEquals(DpOffset(12.dp, 34.dp), state.offset)
    }
}
