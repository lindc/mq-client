package com.mqclient.mqui.command

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

private val SuccessGreen = Color(0xFF4CAF50)
private val FailedRed = Color(0xFFF44336)

/**
 * 状态栏：就绪提示 / 命令数 / 成功失败计数 / 总耗时。计数为零的段不显示。
 */
@Composable
fun CommandStatusBar(
    commandCount: Int,
    successCount: Int,
    failedCount: Int,
    totalDuration: Long,
    isExecuting: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (isExecuting) stringResource("console_status_executing")
            else stringResource("console_status_ready"),
            style = MaterialTheme.typography.bodySmall,
        )
        if (commandCount > 0) {
            Text(
                text = stringResource("console_status_commands").withArg(commandCount),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (successCount > 0) {
            Text(
                text = stringResource("console_status_success").withArg(successCount),
                style = MaterialTheme.typography.bodySmall,
                color = SuccessGreen,
            )
        }
        if (failedCount > 0) {
            Text(
                text = stringResource("console_status_failed").withArg(failedCount),
                style = MaterialTheme.typography.bodySmall,
                color = FailedRed,
            )
        }
        if (totalDuration > 0) {
            Text(
                text = stringResource("console_status_duration").withArg(totalDuration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
