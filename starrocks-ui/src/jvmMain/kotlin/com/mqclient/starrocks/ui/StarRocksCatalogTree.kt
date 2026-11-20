package com.mqclient.starrocks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mqclient.starrocks.catalog.CatalogTable
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 树行通用外壳：缩进引导线（每层一条竖线）+ 单击 / 双击回调 + 内容。
 *
 * 单击**立即**回调，不等双击超时 —— 选表要立刻出结构，不能为了双击识别搭上 300ms。
 */
@Composable
internal fun TreeRow(
    depth: Int,
    onClick: (() -> Unit)? = null,
    onDoubleClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        if (depth > 0) {
            Box(Modifier.matchParentSize()) {
                repeat(depth) { i ->
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = (i * 16 + 8).dp)
                            .fillMaxHeight()
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    )
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = (depth * 16).dp)
                .fillMaxWidth()
                .let { m ->
                    if (onClick == null) {
                        m
                    } else {
                        m.pointerInput(Unit) {
                            detectTapAndDoubleTap(onTap = onClick, onDoubleTap = onDoubleClick)
                        }
                    }
                }
                .padding(vertical = 6.dp),
        ) {
            content()
        }
    }
}

/**
 * 首击立即 [onTap]，双击窗口内的第二击再补一次 [onDoubleTap]。
 *
 * 不用 `detectTapGestures(onTap, onDoubleTap)` 的原因：它必须等过双击超时（~300ms）
 * 才能确定「不是双击」，于是单击选表会明显卡顿。这里的语义是「先选中，再打开控制台」，
 * 双击时首击的选中动作已经完成，正好是用户预期的顺序。
 */
private suspend fun PointerInputScope.detectTapAndDoubleTap(
    onTap: () -> Unit,
    onDoubleTap: (() -> Unit)? = null,
) {
    var lastTapUptime = 0L
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        val isDoubleTap = onDoubleTap != null &&
            up.uptimeMillis - lastTapUptime <= viewConfiguration.doubleTapTimeoutMillis
        lastTapUptime = up.uptimeMillis
        if (isDoubleTap) onDoubleTap() else onTap()
    }
}

internal fun LazyListScope.folderItem(
    label: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    materializedView: Boolean = false,
) {
    item {
        TreeRow(depth = 1, onClick = onToggle) {
            Chevron(expanded)
            Icon(
                if (materializedView) Icons.Filled.Layers else Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp).size(16.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 4.dp),
            )
            Text(
                stringResource("olap.starrocks.catalog.folder_count").withArg(count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/**
 * 表 / 物化视图行：单行导航项，**不再展开字段或分区**。字段在右侧二维表格，
 * 分区在右侧概览底部的折叠小节；树只回答「有哪些对象」。
 *
 * 单击选中（右侧立即加载结构），双击打开 SQL 控制台并填入 `SELECT * ... LIMIT 100`。
 */
internal fun LazyListScope.tableItems(
    entries: List<CatalogTable>,
    keyPrefix: String,
    selection: CatalogSelection?,
    onSelect: (CatalogTable) -> Unit,
    onOpenConsole: (CatalogTable) -> Unit,
) {
    items(entries, key = { "$keyPrefix:${it.name}" }) { table ->
        val selected = (selection as? CatalogSelection.Table)?.name == table.name
        TreeRow(
            depth = 2,
            onClick = { onSelect(table) },
            onDoubleClick = { onOpenConsole(table) },
        ) {
            Icon(
                when {
                    table.materializedView -> Icons.Filled.Layers
                    table.type == "VIEW" -> Icons.Filled.Visibility
                    else -> Icons.Filled.TableChart
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp).size(16.dp),
            )
            Column(Modifier.padding(start = 4.dp).weight(1f)) {
                Text(
                    table.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                if (table.comment.isNotBlank()) {
                    Text(
                        table.comment,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
