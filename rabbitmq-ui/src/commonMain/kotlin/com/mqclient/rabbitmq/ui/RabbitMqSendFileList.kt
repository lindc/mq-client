package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.CompactControlSpec
import com.mqclient.uicommon.CompactRowActionButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.table.formatSize

/**
 * 批量发送文件的状态机：等待 → 发送中 → 成功 / 失败（失败可重试）。
 *
 * 队列发送 Tab 与交换机发送对话框共用同一套状态：两处的文件列表必须长得一模一样，
 * 状态语义分裂就会重新长出两套渲染（历史上「等待」和「成功」字号不一致正是这么来的）。
 */
internal enum class SendFileStatus { WAITING, SENDING, SUCCESS, FAILED }

/**
 * 文件列表列宽：表头与数据行**共用**这组常量。两处各写一个数字时，改了一边、
 * 忘了另一边，表头与数据就会错列。
 */
internal object SendFileColumns {
    val SIZE = 72.dp
    val STATUS = 160.dp
    val ACTIONS = 72.dp
}

private val SuccessGreen = Color(0xFF2E7D32)
private val FailedRed = Color(0xFFD32F2F)

/**
 * 批量发送文件行：等待 / 发送中 / 成功 / 失败 **四态共用同一布局与字号**。
 *
 * 字体规范（全部走 MaterialTheme，杜绝硬编码字号）：
 *  - 文件名 bodyMedium + onSurface 主文字色
 *  - 大小 bodySmall + onSurfaceVariant 次要色
 *  - 状态文字 bodySmall（失败态 error 色）
 *  - 操作按钮为 28dp 透明文字按钮（bodySmall / primary 色，无实心底、无边框），
 *    像电子表格里的行内动作，不与工具栏 / footer 抢焦点
 *
 * 行高以 `heightIn(min = CompactControlSpec.Height)`（32dp）固定：SUCCESS 无按钮、
 * FAILED 错误文案换行、等待时按钮出现/消失，都不会让行高跳动 —— 发送前后列表的
 * 视觉基线因此完全一致。占位比旧 48dp 更紧凑，但「按钮有无不改行高」的语义不变。
 *
 * [modifier] 留给调用方做背景 / 悬浮态；行自身的尺寸与内边距语义由本组件决定。
 */
@Composable
internal fun FileListItem(
    fileName: String,
    fileSize: Long,
    status: SendFileStatus,
    errorMessage: String?,
    busy: Boolean,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = CompactControlSpec.Height),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fileName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatSize(fileSize),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(SendFileColumns.SIZE),
        )
        Box(modifier = Modifier.width(SendFileColumns.STATUS)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (status) {
                    SendFileStatus.WAITING -> {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                        Text(
                            stringResource("rabbitmq_ui_send_status_waiting"),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                    SendFileStatus.SENDING -> {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource("rabbitmq_ui_send_status_sending"),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                    SendFileStatus.SUCCESS -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            stringResource("rabbitmq_ui_send_status_success"),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                        )
                    }
                    SendFileStatus.FAILED -> {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = null,
                            tint = FailedRed,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = errorMessage ?: stringResource("rabbitmq_ui_send_status_failed"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.width(SendFileColumns.ACTIONS)) {
            when {
                status == SendFileStatus.WAITING && !busy ->
                    CompactRowActionButton(stringResource("rabbitmq_ui_send_remove"), onRemove)
                status == SendFileStatus.FAILED && !busy ->
                    CompactRowActionButton(stringResource("rabbitmq_ui_send_retry"), onRetry)
            }
        }
    }
    HorizontalDivider(thickness = 0.5.dp)
}
