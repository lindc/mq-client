package com.mqclient.topology

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.dk.kuiver.KuiverViewerState
import com.dk.kuiver.model.KuiverNode
import com.dk.kuiver.util.calculateNodeBounds
import com.dk.kuiver.util.calculatePositionBounds

/**
 * 拓扑图覆盖式滚动条：kuiver 0.4.2 没有滚动条 UI（只有内建平移手势），这里在画布
 * 上叠加底部水平 / 右侧垂直两条可拖拽 thumb，补齐「图超出容器时能看到滚动条」的预期。
 *
 * 几何模型（全部图空间 dp，与 kuiver 渲染层一致，见 NodeLayer 的放置公式）：
 *
 *     屏幕位置 = 画布中心 + (节点位置 − 图中心) × scale + offset
 *
 * 其中图中心 = calculatePositionBounds 的中心（kuiver 渲染层同源计算），节点位置已
 * 含 KEEP_MANUAL 烘进 layoutedKuiver 的手动位置——拖动节点后 thumb 范围自动更新。
 * 内容包围盒用 calculateNodeBounds（含卡片尺寸 180×56，各面板建图时显式传入）。
 *
 * 行为：
 *  - 内容任一方向超出视口才显示该方向 thumb；「适应视图」（centerGraph）后内容全部
 *    可见，thumb 自动隐藏（fit 的 scale 恒使可视范围 ≥ 内容范围）。
 *  - thumb 拖拽 = 视口窗口平移：graphicsLayer 的 translation 在缩放之外，thumb 位移
 *    与内容屏幕位移 1:1，换算只除 scale 一次，经 [state.updateTransform] 写回 offset。
 *  - thumb 颜色走主题 outlineVariant（拖动中 outline），与项目其它覆盖层一致；容器
 *    Box 不带任何指针处理，空白区域事件原样穿透给下面的 KuiverViewer。
 */
@Composable
fun BoxScope.TopologyScrollbars(
    state: KuiverViewerState,
    modifier: Modifier = Modifier,
) {
    val scale = state.scale
    val canvasW = state.canvasWidth
    val canvasH = state.canvasHeight
    val layouted = state.layoutedKuiver
    if (scale <= 0f || canvasW <= 0.dp || canvasH <= 0.dp || layouted.nodes.isEmpty()) return

    // 内容包围盒（含卡片尺寸）与图中心（纯位置，渲染层同源）。
    // 接收者显式定型 Collection<KuiverNode>：K2 对 Map.values 直接作扩展接收者
    // 在组合上下文里解析不过（实测），定型后正常。
    val (bounds, positions) = remember(layouted) {
        val nodes: Collection<KuiverNode> = layouted.nodes.values
        nodes.calculateNodeBounds() to nodes.calculatePositionBounds()
    }
    val offset = state.offset
    val scheme = MaterialTheme.colorScheme

    // 可视窗口（图空间 dp）：屏幕 [0, canvas] 映射回图坐标。
    val visW = canvasW.value / scale
    val visH = canvasH.value / scale
    val visStartX = windowStart(canvasW.value, positions.centerX.value, offset.x.value, scale)
    val visStartY = windowStart(canvasH.value, positions.centerY.value, offset.y.value, scale)

    Box(modifier.fillMaxSize()) {
        // ── 水平 thumb ──────────────────────────────────────────────────────
        val contentW = bounds.width.value
        if (contentW > visW * FIT_EPSILON) {
            var fracStart = ((visStartX - bounds.minX.value) / contentW).coerceIn(0f, 1f)
            val fracLen = (visW / contentW).coerceIn(0f, 1f)
            if (fracStart + fracLen > 1f) fracStart = 1f - fracLen
            val trackW = canvasW.value - 2f * THUMB_INSET
            var active by remember { mutableStateOf(false) }
            val idleColor = scheme.outlineVariant.copy(alpha = 0.7f)
            val activeColor = scheme.outline
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (THUMB_INSET + fracStart * trackW).dp)
                    .width((fracLen * trackW).dp)
                    .height(THUMB_THICKNESS.dp)
                    .thumbColor(if (active) activeColor else idleColor)
                    .pointerInput(canvasW) {
                        var startVisStart = 0f
                        var startScale = 1f
                        var startOffsetY = 0.dp
                        detectHorizontalDragGestures(
                            onDragStart = {
                                active = true
                                startVisStart = windowStart(
                                    canvasW.value,
                                    positions.centerX.value,
                                    state.offset.x.value,
                                    state.scale,
                                )
                                startScale = state.scale
                                startOffsetY = state.offset.y
                            },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                dragWindow(
                                    state = state,
                                    startVisStart = startVisStart,
                                    delta = amount.toDp().value,
                                    scale = startScale,
                                    canvas = canvasW.value,
                                    fixedOther = startOffsetY,
                                    horizontal = true,
                                )
                            },
                            onDragEnd = { active = false },
                            onDragCancel = { active = false },
                        )
                    },
            )
        }

        // ── 垂直 thumb ──────────────────────────────────────────────────────
        val contentH = bounds.height.value
        if (contentH > visH * FIT_EPSILON) {
            var fracStart = ((visStartY - bounds.minY.value) / contentH).coerceIn(0f, 1f)
            val fracLen = (visH / contentH).coerceIn(0f, 1f)
            if (fracStart + fracLen > 1f) fracStart = 1f - fracLen
            val trackH = canvasH.value - 2f * THUMB_INSET
            var active by remember { mutableStateOf(false) }
            val idleColor = scheme.outlineVariant.copy(alpha = 0.7f)
            val activeColor = scheme.outline
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (THUMB_INSET + fracStart * trackH).dp)
                    .width(THUMB_THICKNESS.dp)
                    .height((fracLen * trackH).dp)
                    .thumbColor(if (active) activeColor else idleColor)
                    .pointerInput(canvasH) {
                        var startVisStart = 0f
                        var startScale = 1f
                        var startOffsetX = 0.dp
                        detectVerticalDragGestures(
                            onDragStart = {
                                active = true
                                startVisStart = windowStart(
                                    canvasH.value,
                                    positions.centerY.value,
                                    state.offset.y.value,
                                    state.scale,
                                )
                                startScale = state.scale
                                startOffsetX = state.offset.x
                            },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                dragWindow(
                                    state = state,
                                    startVisStart = startVisStart,
                                    delta = amount.toDp().value,
                                    scale = startScale,
                                    canvas = canvasH.value,
                                    fixedOther = startOffsetX,
                                    horizontal = false,
                                )
                            },
                            onDragEnd = { active = false },
                            onDragCancel = { active = false },
                        )
                    },
            )
        }
    }
}

/**
 * thumb 拖一步：视口窗口起点前移 [delta]（thumb 与内容屏幕位移 1:1，除 scale 换回图
 * dp），夹在内容范围内后按几何公式反解 offset 写回 state。拖动期间保持拖起时的
 * scale；另一轴 offset 用 [fixedOther] 原样带回。internal 供单元测试。
 */
internal fun dragWindow(
    state: KuiverViewerState,
    startVisStart: Float,
    delta: Float,
    scale: Float,
    canvas: Float,
    fixedOther: Dp,
    horizontal: Boolean,
) {
    // 拖动中图不会变（节点拖拽与 thumb 拖拽互斥），bounds/中心按当前图取即可。
    val layouted = state.layoutedKuiver
    if (layouted.nodes.isEmpty()) return
    val nodes: Collection<KuiverNode> = layouted.nodes.values
    val bounds = nodes.calculateNodeBounds()
    val positions = nodes.calculatePositionBounds()
    val visLen = canvas / scale
    val minStart = if (horizontal) bounds.minX.value else bounds.minY.value
    val maxStart = (if (horizontal) bounds.maxX.value else bounds.maxY.value) - visLen
    if (maxStart < minStart) return
    val newStart = (startVisStart + delta / scale).coerceIn(minStart, maxStart)
    val graphCenter = if (horizontal) positions.centerX.value else positions.centerY.value
    val newOffset = -canvas / 2f - scale * (newStart - graphCenter)
    state.updateTransform(
        scale = scale,
        offset = if (horizontal) DpOffset(newOffset.dp, fixedOther) else DpOffset(fixedOther, newOffset.dp),
        animated = false,
    )
}

/** 屏幕原点对应的图坐标：p = (0 − 画布中心 − offset) / scale + 图中心。internal 供单元测试。 */
internal fun windowStart(canvas: Float, graphCenter: Float, offset: Float, scale: Float): Float =
    graphCenter + (-canvas / 2f - offset) / scale

private fun Modifier.thumbColor(color: Color): Modifier =
    background(color, RoundedCornerShape(THUMB_CORNER.dp))

/** 内容仅比视口大一点点时视为放得下，不显示 thumb（避免「适应视图」后残留碎条）。 */
private const val FIT_EPSILON = 1.02f

/** thumb 离画布边缘的内缩（两对称边合计 2 × 值），也给相邻覆盖层让位。 */
private const val THUMB_INSET = 12f

/** thumb 粗细（dp）。 */
private const val THUMB_THICKNESS = 6f

/** thumb 圆角（dp）。 */
private const val THUMB_CORNER = 3f
