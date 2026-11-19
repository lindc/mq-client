package com.mqclient.starrocks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * VS Code 风格代码块配色（Profile 查看器 / 数据目录 DDL 查看器**共用**）。
 *
 * 统一两套预设是「浅色模式下两个模块视觉一致」的关键：两者不再各自用
 * `surfaceVariant` / 自定义浅灰，而是都取同一份 [codeBlockColors] 预设。
 */
internal data class CodeBlockColors(
    val bg: Color,
    val text: Color,
    val border: Color,
    val keyword: Color,
    val number: Color,
)

/** 深色（默认）：VS Code Dark+ 风格，背景较 #1E1E1E 略亮 + 极细浅边框，避免与白色弹窗割裂。 */
private val CodeBlockDark = CodeBlockColors(
    bg = Color(0xFF252526),
    text = Color(0xFFD4D4D4),
    border = Color(0xFF333333),
    keyword = Color(0xFF569CD6),   // 关键字 / 指标名：VS Code 浅蓝
    number = Color(0xFFB5CEA8),    // 数字：VS Code 浅绿
)

/** 浅色：与 DDL 查看器统一——浅灰底 + 深灰字 + 细边框，类似打印出来的代码。 */
private val CodeBlockLight = CodeBlockColors(
    bg = Color(0xFFF5F5F5),
    text = Color(0xFF333333),
    border = Color(0xFFE0E0E0),
    keyword = Color(0xFF0451A5),
    number = Color(0xFF098658),
)

internal fun codeBlockColors(dark: Boolean): CodeBlockColors =
    if (dark) CodeBlockDark else CodeBlockLight

/** 当前 app 主题是否深色（DDL 查看器跟随 app 主题选深 / 浅代码块配色）。 */
@Composable
internal fun isAppThemeDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * 共享代码块容器：8dp 圆角 + 1dp 细边框 + 12dp 内边距 + 等宽字体 + 20sp 行高 +
 * 可选中复制。Profile 大文本与 DDL 都用它 —— 样式天然一致，不再各写一套。
 */
@Composable
internal fun CodeBlock(
    text: AnnotatedString,
    colors: CodeBlockColors,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.bg,
        border = BorderStroke(1.dp, colors.border),
        modifier = modifier,
    ) {
        SelectionContainer {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colors.text,
                    lineHeight = 20.sp,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}
