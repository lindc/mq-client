package com.mqclient.ibmmq.ui.command

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mqclient.ibmmq.ui.command.model.CommandLine
import com.mqclient.ibmmq.ui.command.model.CommandStatus

/** 语义色：成功绿 / 失败红。与侧栏 statusColor 同理，不走主题种子色（会被用户调跑）。 */
private val SuccessGreen = Color(0xFF4CAF50)
private val FailedRed = Color(0xFFF44336)

/**
 * 编辑器状态列：每行一枚状态图标。
 * NOT_EXECUTED 用自绘 10×2dp 横线（不依赖任何字形），EXECUTING 用进度圈，
 * 其余用 Material Icons —— 全程零 Emoji。
 */
@Composable
fun StatusColumn(
    commands: List<CommandLine>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(32.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
    ) {
        commands.forEach { cmd ->
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when (cmd.status) {
                    CommandStatus.NOT_EXECUTED -> Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(2.dp)
                            .background(Color.Gray),
                    )
                    CommandStatus.EXECUTING -> CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                    )
                    CommandStatus.SUCCESS -> StatusIcon(Icons.Default.CheckCircle, SuccessGreen)
                    CommandStatus.FAILED -> StatusIcon(Icons.Default.Cancel, FailedRed)
                    CommandStatus.SKIPPED -> StatusIcon(Icons.Default.SkipNext, Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(14.dp),
    )
}
