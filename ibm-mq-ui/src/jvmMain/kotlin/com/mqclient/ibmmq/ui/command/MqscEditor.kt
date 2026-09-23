package com.mqclient.ibmmq.ui.command

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.ibmmq.ui.command.model.CommandStatus

private const val LINE_HEIGHT_SP = 24

/**
 * MQSC 编辑器：状态列 + 行号列 + 命令文本，三列共享同一个竖向滚动。
 *
 * 行高对齐：文本 lineHeight 固定 24sp，与状态列 / 行号列的 24dp 行高匹配
 * （默认 fontScale 下逐行对齐；大字号系统下会有像素级漂移，可接受）。
 */
@Composable
fun MqscEditor(
    commands: List<com.mqclient.ibmmq.ui.command.model.CommandLine>,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()

    Row(modifier = modifier.fillMaxSize()) {
        StatusColumn(
            commands = commands,
            modifier = Modifier.verticalScroll(scrollState),
        )
        LineNumberColumn(
            lineCount = maxOf(commands.size, 10),
            modifier = Modifier.verticalScroll(scrollState),
        )
        BasicTextField(
            value = commands.joinToString("\n") { it.text },
            onValueChange = onTextChange,
            visualTransformation = MqscSyntaxTransformation(),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = LINE_HEIGHT_SP.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(scrollState)
                .padding(horizontal = 8.dp, vertical = 0.dp),
        )
    }
}
