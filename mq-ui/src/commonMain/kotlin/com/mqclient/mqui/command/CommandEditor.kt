package com.mqclient.mqui.command

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.mqui.command.api.SyntaxHighlighter
import com.mqclient.mqui.command.model.CommandLine

private const val LINE_HEIGHT_SP = 24

/**
 * 命令编辑器：状态列 + 行号列 + 命令文本，三列共享同一个竖向滚动。
 *
 * 行高对齐：文本 lineHeight 固定 24sp，与状态列 / 行号列的 24dp 行高匹配
 * （默认 fontScale 下逐行对齐；大字号系统下会有像素级漂移，可接受）。
 *
 * 语法高亮由注入的 [SyntaxHighlighter] 提供（各 MQ 模块实现），以
 * VisualTransformation 包装 —— 纯展示层变换，不影响输入本身。
 *
 * 文本组件保持 [BasicTextField]，但 value 用 [TextFieldValue]（而非纯 String），
 * 以便读取选中态（执行选中文本）。
 */
@Composable
fun CommandEditor(
    commands: List<CommandLine>,
    textFieldValue: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    syntaxHighlighter: SyntaxHighlighter,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val transformation = remember(syntaxHighlighter) {
        VisualTransformation { text ->
            TransformedText(syntaxHighlighter.highlight(text), OffsetMapping.Identity)
        }
    }

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
            value = textFieldValue,
            onValueChange = onValueChange,
            visualTransformation = transformation,
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
