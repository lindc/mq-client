package com.mqclient.topology

import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.assertFailsWith
import com.dk.kuiver.model.Kuiver
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.model.NodeDimensions
import com.dk.kuiver.model.buildKuiver
import com.mqclient.graph.TopologyNodeType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 导出尺寸计划 + 离屏渲染冒烟测试。
 *
 * 尺寸断言全部按「逻辑 dp → 物理像素」直算：两个 180×56 卡片（中心 0 / 200）在
 * 48dp padding 下的内容盒 x ∈ [−138, 338]、y ∈ [−76, 76]，即 476×152 dp；2x 后
 * 952×304 px。曲线边若外抛会扩大包围盒，所以这里用端点同高的水平边（曲线外抛
 * 25dp，仍在 48dp padding 内）。
 */
class TopologyImageExportTest {

    private fun graph(
        secondX: Float = 200f,
        withEdge: Boolean = true,
    ): Kuiver = buildKuiver {
        addNode(KuiverNode("A", NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp), DpOffset(0.dp, 0.dp)))
        addNode(
            KuiverNode(
                "B",
                NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp),
                DpOffset(secondX.dp, 0.dp),
            ),
        )
        if (withEdge) addEdge(KuiverEdge("A", "B"))
    }

    private fun theme() = TopologyExportTheme(
        colorScheme = lightColorScheme(),
        typography = Typography(),
        shapes = Shapes(),
    )

    @Test
    fun `plan - 包围盒含 padding - 2x 像素尺寸正确`() {
        val plan = planTopologyExport(graph(), requestedScale = 2f)

        assertEquals(-138f, plan.originX)
        assertEquals(-76f, plan.originY)
        assertEquals(476f, plan.widthDp)
        assertEquals(152f, plan.heightDp)
        assertEquals(952, plan.widthPx)
        assertEquals(304, plan.heightPx)
        assertEquals(2f, plan.scale)
    }

    @Test
    fun `plan - 超大图自动降 scale - 单边不超过 8000px`() {
        val plan = planTopologyExport(graph(secondX = 100_000f), requestedScale = 2f)

        assertTrue(plan.scale < 2f, "effective scale should be reduced")
        assertTrue(plan.widthPx <= TOPOLOGY_EXPORT_MAX_SIDE_PX)
        assertTrue(plan.heightPx <= TOPOLOGY_EXPORT_MAX_SIDE_PX)
        // 完整全图仍然落在图片里（宽度按有效倍率铺满，不被裁）。
        assertTrue(plan.widthPx >= TOPOLOGY_EXPORT_MAX_SIDE_PX - 1)
    }

    @Test
    fun `plan - 1x 请求按 1x 输出`() {
        val plan = planTopologyExport(graph(), requestedScale = 1f)

        assertEquals(476, plan.widthPx)
        assertEquals(152, plan.heightPx)
        assertEquals(1f, plan.scale)
    }

    @Test
    fun `plan - 空图拒绝导出`() {
        assertFailsWith<IllegalArgumentException> {
            planTopologyExport(Kuiver())
        }
    }

    @Test
    fun `render - 输出合法 PNG - 尺寸与计划一致`() {
        val image = renderTopologyPng(
            graph = graph(),
            nodeTypes = mapOf(
                "A" to TopologyNodeType.LOCAL_QUEUE,
                "B" to TopologyNodeType.CHANNEL,
            ),
            fallbackType = TopologyNodeType.LOCAL_QUEUE,
            theme = theme(),
            scale = 2f,
        )

        assertEquals(952, image.widthPx)
        assertEquals(304, image.heightPx)
        assertEquals(2f, image.scale)
        // PNG 魔数：89 50 4E 47 0D 0A 1A 0A
        assertTrue(
            image.pngBytes.size > 8 &&
                image.pngBytes[0] == 0x89.toByte() &&
                image.pngBytes[1] == 'P'.code.toByte() &&
                image.pngBytes[2] == 'N'.code.toByte() &&
                image.pngBytes[3] == 'G'.code.toByte(),
            "expected PNG bytes, got ${image.pngBytes.size} bytes",
        )

        // 内容确实画上去了：背景角点是纯白，图内存在非白像素（节点 / 连线）。
        val decoded = ImageIO.read(ByteArrayInputStream(image.pngBytes))
        assertEquals(952, decoded.width)
        assertEquals(304, decoded.height)
        assertEquals(0xFFFFFFFF.toInt(), decoded.getRGB(0, 0))
        var nonWhite = 0
        for (y in 0 until decoded.height step 4) {
            for (x in 0 until decoded.width step 4) {
                if (decoded.getRGB(x, y) != 0xFFFFFFFF.toInt()) nonWhite++
            }
        }
        assertTrue(nonWhite > 0, "render produced a blank image")
    }

    /**
     * 锚点盲区：边带 `fromAnchor` / `toAnchor` 时导出必须**直接失败**。
     *
     * 锚点位置在图库组合期注册进 internal 的 AnchorRegistry，离屏场景读不到 ——
     * 静默降级到几何端点会画出一条「看起来正常、接点却错了」的边。
     */
    @Test
    fun `render - 带锚点的边拒绝导出 - 不静默画错接点`() {
        val anchored = buildKuiver {
            addNode(KuiverNode("A", NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp), DpOffset(0.dp, 0.dp)))
            addNode(KuiverNode("B", NodeDimensions(CARD_WIDTH.dp, CARD_HEIGHT.dp), DpOffset(200.dp, 0.dp)))
            addEdge(KuiverEdge(fromId = "A", toId = "B", fromAnchor = "right", toAnchor = "left"))
        }

        val error = assertFailsWith<IllegalStateException> {
            renderTopologyPng(
                graph = anchored,
                nodeTypes = mapOf("A" to TopologyNodeType.LOCAL_QUEUE, "B" to TopologyNodeType.CHANNEL),
                fallbackType = TopologyNodeType.LOCAL_QUEUE,
                theme = theme(),
                scale = 1f,
            )
        }
        // 错误信息要能让用户/开发者知道是锚点导致的，而不是「导出坏了」。
        assertTrue(error.message?.contains("KuiverAnchor") == true, "实际文案：${error.message}")
        assertTrue(error.message?.contains("A → B") == true, "应指出具体边：${error.message}")
    }

    @Test
    fun `render - edgeCustomize 传入的定制被真正应用`() {
        var invoked = 0
        renderTopologyPng(
            graph = graph(),
            nodeTypes = mapOf("A" to TopologyNodeType.LOCAL_QUEUE, "B" to TopologyNodeType.CHANNEL),
            fallbackType = TopologyNodeType.LOCAL_QUEUE,
            theme = theme(),
            scale = 1f,
            // 与 TopologyCanvas 的 edgeCustomize 同名同义：面板的按边定制必须进导出图。
            edgeCustomize = { _, base ->
                invoked++
                base.copy(dashed = true)
            },
        )
        assertEquals(1, invoked, "图里有 1 条边，edgeCustomize 应被调用一次")
    }
}
