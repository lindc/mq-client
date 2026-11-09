package com.mqclient.uicommon

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 工具栏 / 表单紧凑控件的统一规格：32dp 高、bodySmall 字、无 elevation。
 *
 * 与底部 40dp 的主流程按钮（[PrimaryButton] / [SecondaryButton]）形成明显的主次对比；
 * 高度与 [CompactControlSpec.Height] 对齐 [com.mqclient.mqui.common.CompactSearchField]
 * 和扁平工具栏按钮，三者并排不参差。底部主按钮**不使用**本套组件。
 */
object CompactControlSpec {
    val Height: Dp = 32.dp
    val ContentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
    val IconSize: Dp = 16.dp
}

/** 工具栏主操作（如「读取」「拉取」「选择文件」）：32dp 实心 primary 底，不投影。 */
@Composable
fun CompactPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(CompactControlSpec.Height),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        elevation = null,
        contentPadding = CompactControlSpec.ContentPadding,
    ) {
        ProvideTextStyle(MaterialTheme.typography.bodySmall) { content() }
    }
}

/** 工具栏次级操作（如「清空」）：32dp 浅绿底 + primary 字，不投影。 */
@Composable
fun CompactSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(CompactControlSpec.Height),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        elevation = null,
        contentPadding = CompactControlSpec.ContentPadding,
    ) {
        ProvideTextStyle(MaterialTheme.typography.bodySmall) { content() }
    }
}

/**
 * 工具栏下拉（时间范围 / 持久化 / 优先级 / 起始位置等）：32dp，替换各模块私有的
 * DropdownSelector / DropdownButton。[options] 为「显示文字 → 值」，泛型由调用方决定。
 */
@Composable
fun <T> CompactDropdown(
    selected: String,
    options: List<Pair<String, T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        CompactSecondaryButton(onClick = { expanded = true }) {
            Text(selected)
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(CompactControlSpec.IconSize),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}
