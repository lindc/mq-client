package com.mqclient.ibmmq.ui.command

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 行号列：与编辑器行对齐（每行 24dp，与文本行高一致）。空行也补足 [lineCount]
 * 行，避免编辑器行数太少时两列高度塌掉。
 */
@Composable
fun LineNumberColumn(
    lineCount: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(40.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
    ) {
        repeat(lineCount) { index ->
            Box(
                modifier = Modifier
                    .height(24.dp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}
