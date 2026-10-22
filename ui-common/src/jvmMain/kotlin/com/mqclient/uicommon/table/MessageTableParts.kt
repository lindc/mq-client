package com.mqclient.uicommon.table

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.AppTooltip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 消息表格的通用零件：表头文本、行内图标操作钮、大小 / 时间列格式化。
 *
 * 为什么在这里：三套消息浏览页（IBM MQ / Kafka / RocketMQ）此前各自复制了一份，
 * 逐字相同（`TableHeaderText` 三份、`RowActionIcon` 三份、`formatSize` 五份）。
 * 复制件的风险不是行数，而是**改一处漏两处** —— 例如禁用态透明度只在一个模块修过。
 *
 * 只收真正逐字重复的叶子组件；整页（分页 / 起点选择 / 三态）各中间件语义差异大，
 * 仍留在各自模块（页面级统一是另一件事）。
 */

/** 表头单元格文本：labelLarge + 次要色 + 单行。 */
@Composable
fun TableHeaderText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * 表格行内的 24dp 图标操作钮。
 *
 * 执行中显示 16dp 进度圈并禁用点击（避免连点重复提交）；未启用时图标降到 38% 透明
 * —— 与 Material 的 disabled 语义一致（`IconButton(enabled=false)` 本身不改 tint）。
 */
@Composable
fun TableRowActionIcon(
    icon: ImageVector,
    tooltip: String,
    enabled: Boolean = true,
    executing: Boolean = false,
    onClick: () -> Unit = {},
) {
    AppTooltip(tooltip) {
        IconButton(
            onClick = onClick,
            enabled = enabled && !executing,
            modifier = Modifier.size(24.dp),
        ) {
            if (executing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = tooltip,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (enabled) 1f else 0.38f),
                )
            }
        }
    }
}

/** 时间戳列的格式：`yyyy-MM-dd HH:mm:ss`（本地时区）。 */
private val TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * 大小列：B / KB / MB（各保留 1 位小数）。
 *
 * 统一后用 MB 档：此前 MQ / Kafka / RocketMQ 只到 KB，大消息会显示成
 * `2048.0 KB`；RabbitMQ 那份有 MB。取超集，纯显示变化。
 */
fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/** 时间戳列：epoch millis → 本地时区文本；`<= 0`（无时间戳 / epoch 0）显示 "-"。 */
fun formatTimestamp(millis: Long): String =
    if (millis <= 0) {
        "-"
    } else {
        TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    }
