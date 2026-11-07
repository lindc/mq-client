package com.mqclient.mqui.send

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.AppTooltip
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.table.formatSize

/** 一行文件发送任务的宽度约定（文件名自适应，其余三列定宽）。 */
private val SIZE_COLUMN = 80.dp
private val STATUS_COLUMN = 110.dp
private val ACTION_COLUMN = 80.dp
private const val ROW_HPADDING = 12

/** 「等待 / 发送中 / 成功 / 失败」四态（各 MQ 模块的发送任务共用这套语义）。 */
enum class SendTaskStatus { WAITING, SENDING, SUCCESS, FAILED }

/**
 * 「按文件批量发送」的一行（**三个模块共用**：IBM MQ / Kafka / RocketMQ）。
 *
 * 为什么抽出来：这段行渲染（悬停底色、文件名 tooltip、大小列、状态图标 + 文案、
 * 行尾动作按钮、失败原因 hover）在三个 `*MessageSendTab` 里是逐字副本，约 65 行 × 3。
 * 协议差异（分区 / tag / persistence 等）都在**表单字段区**，与这一行无关 ——
 * 所以这里只收「一行任务长什么样」，不收各协议的草稿与发送逻辑。
 *
 * @param fileName 文件名（超出省略，悬停显示全名）。
 * @param sizeBytes 文件字节数（按 `formatSize` 展示）。
 * @param status 四态之一，决定图标与颜色。
 * @param statusText **已本地化**的状态文案（各模块自己的 i18n 键）。
 * @param errorMessage 失败原因（hover 显示；null = 不显示）。
 * @param actionLabel 行尾动作按钮文案（「移除」/「重试」）；null = 该行无动作。
 * @param onAction 动作回调（与 [actionLabel] 成对出现）。
 */
@Composable
fun SendTaskRow(
    fileName: String,
    sizeBytes: Long,
    status: SendTaskStatus,
    statusText: String,
    errorMessage: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = if (hovered) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(background)
            .padding(horizontal = ROW_HPADDING.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppTooltip(fileName, Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = formatSize(sizeBytes),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.width(SIZE_COLUMN),
        )
        Box(modifier = Modifier.width(STATUS_COLUMN)) {
            SendStatusCell(status, statusText, errorMessage)
        }
        // 操作列始终预留按钮的占位高度：等待态有「移除」按钮撑开行高，
        // 成功态无按钮时若不留空，行高会塌缩 —— 正是「发送后间距消失」的根源。
        // 这里给固定 min 高度 + 居中，按钮有无都不改变行高，发送前后完全一致。
        Box(
            modifier = Modifier.width(ACTION_COLUMN).heightIn(min = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (actionLabel != null && onAction != null) {
                SmallSendButton(actionLabel, onAction)
            }
        }
    }
}

/** 状态列：图标 + 文案；失败项悬停显示原因。 */
@Composable
private fun SendStatusCell(status: SendTaskStatus, statusText: String, errorMessage: String?) {
    // 状态文字色：成功绿、失败红，与图标同色；等待 / 发送中走默认文字色。
    // 这样「等待 → 成功 / 失败」只换图标与颜色，字号与列宽不动，行高零跳动。
    val textColor: Color = when (status) {
        SendTaskStatus.SUCCESS -> SuccessGreen
        SendTaskStatus.FAILED -> FailedRed
        else -> Color.Unspecified
    }
    val cell: @Composable () -> Unit = {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (status) {
                SendTaskStatus.WAITING -> Box(Modifier.size(8.dp).clip(CircleShape).background(WaitingGray))
                SendTaskStatus.SENDING -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                )
                SendTaskStatus.SUCCESS -> Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = SuccessGreen,
                )
                SendTaskStatus.FAILED -> Icon(
                    imageVector = Icons.Default.Cancel,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = FailedRed,
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                maxLines = 1,
            )
        }
    }
    if (status == SendTaskStatus.FAILED && errorMessage != null) {
        AppTooltip(errorMessage, Modifier) { cell() }
    } else {
        cell()
    }
}

/**
 * 发送页里的小号次按钮（「移除」「重试」「再发一次」）。
 *
 * 与 [SecondaryButton] 同一套视觉，只是去掉最小宽度 —— 它是**行内**动作，跟表格列
 * 对齐而不是跟对话框 footer 对齐。公开出来是因为三个模块的底部栏也要用同一颗。
 */
@Composable
fun SmallSendButton(text: String, onClick: () -> Unit) {
    SecondaryButton(onClick = onClick, standardMinWidth = false) { Text(text) }
}

/** 状态色：与项目语义色约定一致（不随主题翻转）。 */
private val SuccessGreen = Color(0xFF4CAF50)
private val FailedRed = Color(0xFFF44336)
private val WaitingGray = Color(0xFF9E9E9E)
