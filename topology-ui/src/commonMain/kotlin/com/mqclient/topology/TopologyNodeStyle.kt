package com.mqclient.topology

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.mqclient.graph.TopologyNode

/**
 * 节点样式契约：**业务模块**实现（图标 / 强调色都是业务语义），渲染层只调用。
 *
 * 实现方用自己的类型枚举做穷尽 `when`——漏配一个类型是编译错误；兜底分支只处理
 * 「type 为 null / 不是本模块的类型」这一类真正的未知情况。
 *
 * 放在 commonMain：业务模块的类型枚举都定义在自己的 commonMain（builder 也在那里），
 * 接口必须能在 common 源集里被实现。
 */
interface TopologyNodeStyleProvider {
    fun getIcon(node: TopologyNode): ImageVector
    fun getColor(node: TopologyNode): Color
}

/** 未知类型的中性灰：与既有 QMGR 系灰同值，不与任何业务色撞车。 */
private val UNKNOWN_TYPE_COLOR = Color(0xFF6B7280)

/**
 * 未提供样式时的兜底：问号 + 中性灰。
 *
 * 只在两种情况下出现，且都**不该**在正式界面里看到：
 *  - builder 漏标节点类型（type == null）；
 *  - 某个 Composable 忘了传 `style`（公开 API 是必填参数，漏传是编译错误；
 *    本对象只做 `LocalTopologyNodeStyle` 的默认值）。
 * 故意做成「一眼看出不对」，而不是静默画成某个业务类型的样子。
 */
object FallbackTopologyNodeStyle : TopologyNodeStyleProvider {
    override fun getIcon(node: TopologyNode): ImageVector = Icons.Filled.HelpOutline
    override fun getColor(node: TopologyNode): Color = UNKNOWN_TYPE_COLOR
}

/**
 * 样式管道（可选）：`TopologyImageExporter` 的离屏 composition 用它补一次，
 * 卡片内部就不必再穿参。
 *
 * 顶层 API（[TopologyCanvas] / [renderTopologyPng]）仍然坚持**显式必填参数**：
 * 漏传是编译错误，而 CL 的默认值只会把漏传变成静默的视觉缺陷。
 * 只在「跨 composition 边界」（kuiver 的 nodeContent 槽、离屏场景）才值得往下铺。
 */
val LocalTopologyNodeStyle = staticCompositionLocalOf<TopologyNodeStyleProvider> {
    FallbackTopologyNodeStyle
}
