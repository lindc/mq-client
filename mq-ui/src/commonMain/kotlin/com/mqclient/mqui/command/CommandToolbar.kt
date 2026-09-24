package com.mqclient.mqui.command

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource

/**
 * 控制台工具栏（DBeaver 风格）：扁平按钮（[ToolbarButton]），执行中右侧小进度圈，
 * 底部细分隔线与编辑区隔开。按钮语义全类型通用：执行 / 取消 / 清空 /
 * 打开文件 / 导出结果。
 */
@Composable
fun CommandToolbar(
    isExecuting: Boolean,
    onExecute: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onOpenFile: () -> Unit,
    onExport: () -> Unit,
    hasOutput: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton(
            icon = Icons.Default.PlayArrow,
            text = stringResource("console_execute"),
            onClick = onExecute,
            enabled = !isExecuting,
        )
        ToolbarButton(
            icon = Icons.Default.Stop,
            text = stringResource("console_cancel"),
            onClick = onCancel,
            enabled = isExecuting,
        )
        ToolbarButton(
            icon = Icons.Default.Delete,
            text = stringResource("console_clear"),
            onClick = onClear,
            enabled = !isExecuting,
        )
        ToolbarButton(
            icon = Icons.Default.FolderOpen,
            text = stringResource("console_open_file"),
            onClick = onOpenFile,
            enabled = !isExecuting,
        )
        ToolbarButton(
            icon = Icons.Default.FileDownload,
            text = stringResource("console_export"),
            onClick = onExport,
            enabled = !isExecuting && hasOutput,
        )
        Spacer(Modifier.weight(1f))
        if (isExecuting) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
