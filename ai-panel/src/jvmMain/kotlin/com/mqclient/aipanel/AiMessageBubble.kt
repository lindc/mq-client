package com.mqclient.aipanel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One chat bubble plus the action strip below AI bubbles (copy / thumbs).
 * USER messages render right-aligned with the primary container colour;
 * ASSISTANT messages render left-aligned on a subtle surface variant.
 *
 * Icons are plain text glyphs (📋 👍 👎) so the module stays free of the
 * material-icons-extended dependency.
 */
@Composable
fun AiMessageBubble(
    message: AiMessage,
    onFeedback: (Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == Role.USER
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = message.content.ifEmpty { "…" },
                color = textColor,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (isUser) FontWeight.Medium else FontWeight.Normal,
                ),
            )
        }
        if (!isUser) {
            Spacer(Modifier.height(4.dp))
            BubbleActions(
                content = message.content,
                feedback = message.feedback,
                onFeedback = onFeedback,
            )
        }
    }
}

@Composable
private fun BubbleActions(
    content: String,
    feedback: Boolean?,
    onFeedback: (Boolean?) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    // 评价状态以 message.feedback 为准（不是本气泡的局部状态）：这样列表回收 /
    // 流式追加导致气泡重建时，👍/👎 的高亮不会丢。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(content)) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        ) {
            Text("📋", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(4.dp))
            Text("复制", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(
            onClick = { onFeedback(if (feedback == true) null else true) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        ) {
            Text(
                "👍",
                style = MaterialTheme.typography.labelSmall,
                color = if (feedback == true) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text("有用", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(
            onClick = { onFeedback(if (feedback == false) null else false) },
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
        ) {
            Text(
                "👎",
                style = MaterialTheme.typography.labelSmall,
                color = if (feedback == false) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text("无用", style = MaterialTheme.typography.labelSmall)
        }
    }
}