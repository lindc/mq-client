package com.mqclient.app

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource
import mq_client.app_ui.generated.resources.Res
import mq_client.app_ui.generated.resources.ic_menu
import org.jetbrains.compose.resources.painterResource

/**
 * Left end of the title bar: the navigation toggle, a divider, the single
 * 文件 menu bar item, and the breadcrumb trail that follows it.
 *
 * The row spans the full bar so the empty stretch stays part of the drag area,
 * while the [IconButton] and [MenuBarItem]s still swallow their own clicks the
 * same way the window controls do.
 */
@Composable
internal fun TitleBarPanel(
    breadcrumb: List<String>,
    onToggleNavigation: () -> Unit,
    onShowNewConnection: () -> Unit,
    onShowSettings: () -> Unit,
    onShowAbout: () -> Unit,
    onToggleAiPanel: () -> Unit,
    onExit: () -> Unit,
) {
    var fileMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxSize().padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggleNavigation) {
            Icon(
                painter = painterResource(Res.drawable.ic_menu),
                contentDescription = "Toggle navigation",
            )
        }
        VerticalDivider(modifier = Modifier.height(24.dp))
        MenuBarItem(stringResource("menu_file"), fileMenuExpanded, { fileMenuExpanded = it }) {
            // 菜单排版：极简纯列表（无分割线），每项上下 8dp 留出呼吸感；
            // AI 助手右侧带全局快捷键提示（核心卖点）。
            DropdownMenuItem(
                text = { Text(stringResource("menu_new_connection")) },
                // The menu only collapses through its expanded state; an item
                // click does not dismiss it by itself.
                onClick = {
                    fileMenuExpanded = false
                    onShowNewConnection()
                },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource("menu_ai_assistant")) },
                trailingIcon = {
                    Text(
                        text = aiAssistantShortcutHint(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {
                    fileMenuExpanded = false
                    onToggleAiPanel()
                },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource("menu_settings")) },
                onClick = {
                    fileMenuExpanded = false
                    onShowSettings()
                },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource("menu_about")) },
                onClick = {
                    fileMenuExpanded = false
                    onShowAbout()
                },
                modifier = Modifier.padding(vertical = 8.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource("menu_exit")) },
                onClick = {
                    fileMenuExpanded = false
                    onExit()
                },
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        // Only separate the two while there is actually a trail to show.
        if (breadcrumb.isNotEmpty()) {
            VerticalDivider(modifier = Modifier.height(16.dp).padding(horizontal = 8.dp))
        }
        Box(Modifier.weight(1f)) {
            BreadcrumbTrail(breadcrumb)
        }
    }
}

/**
 * `连接 > 对象` trail for whatever the content area is showing: the connection
 * name, plus the object type once a level-2 row (or its tab) is selected.
 */
@Composable
private fun BreadcrumbTrail(segments: List<String>) {
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        segments.forEachIndexed { index, segment ->
            if (index > 0) {
                Text(
                    text = ">",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            Text(
                text = segment,
                style = MaterialTheme.typography.labelLarge,
                color =
                    if (index == segments.lastIndex) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A single top-level menu button that opens a [DropdownMenu] on click. Lives in
 * the title bar, so it behaves like a desktop menu bar item.
 */
@Composable
private fun MenuBarItem(
    label: String,
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val hoverShape = MaterialTheme.shapes.small
    Box(modifier) {
        TextButton(
            onClick = { onExpandChange(true) },
            modifier = Modifier.background(
                color = if (hovered || expanded) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                } else {
                    Color.Transparent
                },
                shape = hoverShape,
            ),
            shape = hoverShape,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            interactionSource = interactionSource,
        ) {
            Text(label)
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandChange(false) },
            content = content,
        )
    }
}

/**
 * AI 助手的全局快捷键提示（菜单项右侧对齐显示，与 Main.kt 的 onPreviewKeyEvent
 * 处理保持同一组合）：macOS 显示 ⇧⌘A，其余平台 Ctrl+Shift+A。
 */
private fun aiAssistantShortcutHint(): String {
    val isMac = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    return if (isMac) "⇧⌘A" else "Ctrl+Shift+A"
}
