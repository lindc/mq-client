package com.mqclient.mqui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.uicommon.i18n.stringResource

private const val FOCUS_ANIM_MS = 150

/** 紧凑尺寸常量：与扁平工具栏按钮（ToolbarButton）同一档高度。 */
private val FieldHeight = 32.dp
private val FieldFontSize = 13.sp
private val IconSize = 14.dp

/**
 * 紧凑搜索框（32dp 定高）：BasicTextField 自绘，替代 56dp 的 OutlinedTextField
 * —— 工具栏里与扁平按钮同高才整齐。样式与工具栏按钮同一套语言：6dp 圆角、
 * 1dp 边框、聚焦 150ms 过渡到主题色。
 *
 * 内置清除按钮：[value] 非空时尾部出现 ✕，点击清空（[onValueChange]("")）。
 * [trailingIcon] 可再追加自定义尾部内容（在清除按钮右侧）。
 */
@Composable
fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: ImageVector? = Icons.Default.Search,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
) {
    var isFocused by remember { mutableStateOf(false) }

    val borderColor by animateColorAsState(
        targetValue =
            if (isFocused) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(FOCUS_ANIM_MS),
        label = "compactSearchBorder",
    )

    Row(
        modifier = modifier
            .height(FieldHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leadingIcon?.let { icon ->
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(IconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 输入区 + placeholder 叠放：BasicTextField 没有 placeholder 参数，
        // 空值时在文字下层垫一层灰字。
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = FieldFontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontSize = FieldFontSize,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .onFocusChanged { isFocused = it.isFocused },
            )
        }

        // 清除按钮：有内容才出现，点击一键清空。
        if (value.isNotEmpty() && enabled) {
            Icon(
                imageVector = Icons.Default.Clear,
                contentDescription = stringResource("common_clear"),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(IconSize)
                    .clip(RoundedCornerShape(3.dp))
                    .clickable { onValueChange("") }
                    .padding(1.dp),
            )
        }

        trailingIcon?.invoke()
    }
}
