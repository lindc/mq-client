package com.mqclient.ibmmq.ui.topology

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dk.kuiver.model.EdgeType
import com.dk.kuiver.model.KuiverEdge
import com.dk.kuiver.ui.EdgeShape
import com.dk.kuiver.ui.EdgeStyle

/**
 * 曲线边的样式工厂：BACK（环）橙色、其余主题灰，形状 [EdgeShape.CURVED]。
 *
 * **为什么不是自绘 Canvas 的 cubicTo**：`KuiverViewer` 的 edgeContent 插槽画在画布的
 * 非变换坐标空间里，自绘曲线在缩放 / 平移后不会跟着变换（要自己复刻 scale + offset，
 * 且箭头端点还得沿曲线重新裁剪）。而 kuiver 内部的 `createCurvedPath` 本身就是
 * cubic 贝塞尔 S 形（两端水平切出 / 切入，控制点在起终点水平延伸处），绘制、缩放、
 * 平移、沿曲线切线的箭头都由库在正确的变换空间里完成——效果即需求描述的曲线，
 * 且不会有变换错位。
 *
 * 颜色由面板传入：正常边用主题色（outlineVariant），环用固定语义橙（异常信号，
 * 不随主题翻转），满足「不硬编码界面铬色」。
 */
internal fun topologyEdgeStyle(
    edge: KuiverEdge,
    normalColor: Color,
    cycleColor: Color,
): EdgeStyle = EdgeStyle(
    color = if (edge.type == EdgeType.BACK) cycleColor else normalColor,
    strokeWidth = 1.5.dp,
    showArrow = true,
    shape = EdgeShape.CURVED,
)
