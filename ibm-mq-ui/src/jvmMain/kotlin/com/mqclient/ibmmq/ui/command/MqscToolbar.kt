package com.mqclient.ibmmq.ui.command

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
 * 底部细分隔线与编辑区隔开。
 */
@Composable
fun MqscToolbar(
    isExecuting: Boolean,
    onExecute: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
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
            text = stringResource("mqsc_execute"),
            onClick = onExecute,
            enabled = !isExecuting,
        )
        ToolbarButton(
            icon = Icons.Default.Stop,
            text = stringResource("mqsc_cancel"),
            onClick = onCancel,
            enabled = isExecuting,
        )
        ToolbarButton(
            icon = Icons.Default.Delete,
            text = stringResource("mqsc_clear"),
            onClick = onClear,
            enabled = !isExecuting,
        )
        Spacer(Modifier.weight(1f))
        if (isExecuting) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
