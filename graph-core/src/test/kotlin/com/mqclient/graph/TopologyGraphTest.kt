package com.mqclient.graph

import com.dk.kuiver.model.Kuiver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 图模型契约（graph-core 的全部公开面）：节点视图 + 一次性构建产物。
 *
 * 节点**词汇表**不在这里 —— 各业务模块用自己的枚举实现 [TopologyNodeType]，
 * 图标 / 颜色 / 布局谓词都在模块侧穷尽校验（编译期兜底），graph-core 只承诺
 * 「节点有类型」这件事本身。
 *
 * 这一层没有逻辑、只有数据契约，所以测试盯的是「容易静默出错」的几条：
 *  - [TopologyNode] 的默认值语义（type 可空、severity 默认 NORMAL、badge 可省）；
 *  - [TopologyNodeType.label] 的默认实现（详情卡副标题取枚举名，改名会改显示文案）；
 *  - [TopologyData] 是数据载体：`copy` 不应意外丢字段。
 */
class TopologyGraphTest {

    private enum class SampleType : TopologyNodeType {
        QUEUE,
        TOPIC,
    }

    /** 类型标签默认取枚举常量名：详情卡 / 无障碍描述都靠它，改名是有意识的视觉改动。 */
    @Test
    fun `label defaults to the enum constant name`() {
        assertEquals("QUEUE", SampleType.QUEUE.label)
        assertEquals("TOPIC", SampleType.TOPIC.label)
    }

    /** 非枚举实现（自定义标记类）不该炸：退化成 toString()。 */
    @Test
    fun `label falls back to toString for non enum types`() {
        val custom = object : TopologyNodeType {}

        assertTrue(custom.label.isNotBlank(), "非枚举实现必须仍能取到非空标签")
    }

    /** 未标注节点用默认值：type 为 null、severity 为 NORMAL、badge 不占位。 */
    @Test
    fun `node defaults are untyped normal and badge-less`() {
        val node = TopologyNode("q1")

        assertNull(node.type, "未标注节点的 type 必须是 null（渲染层据此走兜底样式）")
        assertEquals(NodeSeverity.NORMAL, node.severity)
        assertNull(node.badge, "badge 默认 null：卡片维持纯名称形态")
        assertEquals("q1", node.id)
    }

    @Test
    fun `severity order is normal warning error`() {
        // 渲染层按 ordinal 比较（取更严重的那个），顺序即语义。
        assertEquals(
            listOf("NORMAL", "WARNING", "ERROR"),
            NodeSeverity.entries.map { it.name },
        )
        assertTrue(NodeSeverity.WARNING.ordinal > NodeSeverity.NORMAL.ordinal)
        assertTrue(NodeSeverity.ERROR.ordinal > NodeSeverity.WARNING.ordinal)
    }

    @Test
    fun `data carries the kuiver graph by reference`() {
        val graph = Kuiver()
        val data = TopologyData(
            kuiver = graph,
            nodes = mapOf("p0" to TopologyNode("p0", SampleType.QUEUE, badge = "leader=1")),
        )

        // 不复制图：渲染层直接消费同一个实例（大图上复制没有意义）。
        assertSame(graph, data.kuiver)
        assertEquals("leader=1", data.nodes["p0"]?.badge)
    }

    @Test
    fun `copy keeps fields that were not overridden`() {
        val data = TopologyData(
            kuiver = Kuiver(),
            nodes = mapOf(
                "t" to TopologyNode("t", SampleType.TOPIC, NodeSeverity.ERROR, badge = "p=3"),
            ),
        )

        val updated = data.copy(
            nodes = mapOf("t" to TopologyNode("t", SampleType.TOPIC, NodeSeverity.NORMAL, "p=3")),
        )

        assertEquals(SampleType.TOPIC, updated.nodes["t"]?.type)
        assertEquals("p=3", updated.nodes["t"]?.badge, "copy 未点名的字段必须原样保留")
        assertEquals(NodeSeverity.NORMAL, updated.nodes["t"]?.severity)
    }
}
