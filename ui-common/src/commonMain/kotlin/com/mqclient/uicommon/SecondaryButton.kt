package com.mqclient.uicommon

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * 次要按钮（取消 / 清空 / 重试等）：透明底 + 1dp outline 边框 + primary 文字；
 * 禁用态 = 透明底 + 边框 outline@12% + 文字 onSurface@38%（无实心灰底、无粗边框）。
 * 与 [PressableButton]（主按钮 / danger）同一按压反馈骨架（scale + 阴影），参数同构。
 */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    elevation: Dp = 0.dp,
    pressedScale: Float = 0.96f,
    standardMinWidth: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val containerColor = Color.Transparent
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val borderColor = if (enabled) {
        MaterialTheme.colorScheme.outline
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    }
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, label = "press-scale")
    val minWidthModifier = if (standardMinWidth) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        // 按钮文字样式是 labelLarge（ProvideTextStyle 设置）；主题若没给具体字号则退回 M3 默认 14sp。
        val fontSize = MaterialTheme.typography.labelLarge.fontSize.takeIf { it.isSpecified } ?: 14.sp
        val charWidth = with(density) { fontSize.toDp() }
        val cp = contentPadding
        val horizontalPadding = with(density) {
            cp.calculateLeftPadding(layoutDirection) +
                contentPadding.calculateRightPadding(layoutDirection)
        }
        Modifier.defaultMinSize(minWidth = charWidth * 4 + horizontalPadding)
    } else {
        Modifier
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = if (pressed) 0.dp else elevation,
        interactionSource = interactionSource,
        modifier = modifier
            .then(minWidthModifier)
            .scale(scale),
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
            Row(
                modifier = Modifier.padding(contentPadding),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}
