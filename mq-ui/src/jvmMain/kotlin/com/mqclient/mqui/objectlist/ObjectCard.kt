package com.mqclient.mqui.objectlist

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.core.ContextMenuItem
import com.mqclient.uicommon.i18n.stringResource

private const val HOVER_ANIM_MS = 150

/** 卡片内边距：水平 12dp；垂直 10dp（10 + 20 + 4 + 20 + 10 = 卡片定高 64dp）。 */
private val CardPaddingHorizontal = 12.dp
private val CardPaddingVertical = 10.dp

/** 监听次键（右键）按下，把按下点（本卡片局部坐标）交给 [onSecondaryClick]。 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.onSecondaryPress(onSecondaryClick: (position: Offset) -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { event ->
        if (event.buttons.isSecondaryPressed) onSecondaryClick(event.changes.first().position)
    }

/**
 * 对象卡片：严格 2 行的紧凑卡片。
 *
 * 第 1 行：类型图标（可选）+ 对象名（单行省略）+ "..." 按钮；
 * 第 2 行：状态圆点（可选）+ 状态文字（可选）+ 数值（可选）。
 *
 * 交互：
 *  - 单击 [onClick]、双击 [onDoubleClick]（整卡可点，"..." 按钮除外）；
 *  - 右键与 "..." 按钮共用同一份 [contextMenu] 菜单（[ObjectCardContextMenu]）；
 *  - 悬停背景 / 边框 150ms 过渡。
 */
@Composable
fun ObjectCard(
    data: ObjectCardData,
    contextMenu: List<ContextMenuItem>,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) } // "..." 按钮的菜单（锚定按钮）
    var showContextMenu by remember { mutableStateOf(false) } // 右键菜单（锚定整卡）
    var contextMenuOffset by remember { mutableStateOf(DpOffset.Zero) }
    var cardSizePx by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val backgroundColor by animateColorAsState(
        targetValue =
            if (isHovered) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surface,
        animationSpec = tween(HOVER_ANIM_MS),
        label = "objectCardBackground",
    )
    val borderColor by animateColorAsState(
        targetValue =
            if (isHovered) MaterialTheme.colorScheme.outline
            else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(HOVER_ANIM_MS),
        label = "objectCardBorder",
    )

    Box {
        Surface(
            modifier = modifier
                .onSizeChanged { cardSizePx = it }
                .hoverable(interactionSource)
                // 右键：菜单顶贴光标 —— DropdownMenu 默认挂在锚点（整卡）底边之下，
                // y 倒扣卡高后菜单顶正好落在按点上（同宿主 ContextMenuBox 的换算）。
                // detectTapGestures 不管次键，右键单独用 onPointerEvent 监听。
                .onSecondaryPress { pos ->
                    contextMenuOffset = with(density) {
                        DpOffset(
                            x = pos.x.toDp(),
                            y = pos.y.toDp() - cardSizePx.height.toFloat().toDp(),
                        )
                    }
                    showContextMenu = true
                }
                .pointerInput(onClick, onDoubleClick) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onDoubleTap = { onDoubleClick() },
                    )
                },
            shape = RoundedCornerShape(8.dp),
            color = backgroundColor,
            border = BorderStroke(1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = CardPaddingHorizontal,
                    vertical = CardPaddingVertical,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.heightIn(min = 20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    data.typeIcon?.let { icon ->
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = data.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // "..." 按钮 + 菜单同在一个 Box：DropdownMenu 锚定这个 24dp
                    // 的 Box，菜单出现在按钮正下方（"..." 与右键共用同一份
                    // contextMenu 数据，只是锚点不同 —— 渲染统一走
                    // [ObjectCardContextMenu]）。
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource("object_card_more"),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        ObjectCardContextMenu(
                            expanded = showMenu,
                            items = contextMenu,
                            onDismiss = { showMenu = false },
                        )
                    }
                }
                Row(
                    modifier = Modifier.heightIn(min = 20.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧：状态图标 + 状态文字（都可空，分别判断；文字颜色跟
                    // 图标 tint 保持一致 —— 「绿字配绿勾」才是同一个状态）。
                    if (data.statusIcon != null || data.statusText != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            data.statusIcon?.let { icon ->
                                Icon(
                                    imageVector = icon,
                                    contentDescription = data.statusText,
                                    modifier = Modifier.size(14.dp),
                                    tint = data.statusIconTint
                                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            data.statusText?.let { text ->
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 12.sp,
                                    color = data.statusIconTint
                                        ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    data.metricValue?.let { value ->
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 右键菜单挂在最外层 Box（锚定整卡），offset 定位到光标按点。
        ObjectCardContextMenu(
            expanded = showContextMenu,
            items = contextMenu,
            onDismiss = { showContextMenu = false },
            offset = contextMenuOffset,
        )
    }
}
