package com.mqclient.uicommon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.launch

/**
 * 「Key-Value」属性表（各 MQ 模块属性面板/对话框的统一展示组件）：
 *
 *  - **斑马纹**：奇数行（index 1/3/...）`surfaceVariant` 浅底 + 4dp 微圆角，偶数行透明；
 *  - **对齐**：Key 定宽 120dp 左对齐（次要色），Value `weight(1f)` **左对齐**撑满
 *    （长文本最多两行后省略，不撑破布局），行尾复制按钮补位；
 *  - **可选中**：Value 包在 `SelectionContainer` 里，鼠标可拖选；
 *  - **复制**：行尾 IconButton（24dp / 图标 14dp）点击写剪贴板，Snackbar 提示
 *    「已复制」（复用 i18n `msg_detail_copied`）—— Snackbar host 内置在组件里，
 *    对话框 / 面板中开箱即用，调用方无需接 host；
 *  - **空值**：null / 空串显示 `-`，且不渲染复制按钮（`copyable` 同时为 true 才显示）。
 *
 * 分组场景：小标题 + [PropertyTable]（见 [PropertyTableSection]），不再套卡片 ——
 * 斑马纹本身就是视觉容器，双层背景会互相叠深。
 */
data class PropertyItem(
    val key: String,
    val value: String?,
    val copyable: Boolean = true,
    /** 可选说明行：渲染在 Key 下方的小字（labelSmall 次要色，两行截断）；null 不渲染。 */
    val description: String? = null,
)

/** 属性表：若干 [PropertyItem] 的斑马纹两列展示，行尾复制按钮 + 内置 Snackbar。 */
@Composable
fun PropertyTable(
    items: List<PropertyItem>,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEachIndexed { index, item ->
                val zebra = index % 2 == 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (zebra) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.width(120.dp)) {
                        Text(
                            text = item.key,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!item.description.isNullOrBlank()) {
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    androidx.compose.foundation.text.selection.SelectionContainer(
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = item.value ?: "-",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.copyable && !item.value.isNullOrBlank()) {
                        IconButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(item.value.orEmpty()))
                                scope.launch {
                                    snackbarHostState.showSnackbar(stringResource("msg_detail_copied"))
                                }
                            },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = item.key,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }
        ThemedSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * 分组便捷封装：小标题 + [PropertyTable]（属性对话框「基本信息 / 统计指标」这类
 * 分节场景用）。
 */
@Composable
fun PropertyTableSection(
    title: String,
    items: List<PropertyItem>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        PropertyTable(items = items)
    }
}
