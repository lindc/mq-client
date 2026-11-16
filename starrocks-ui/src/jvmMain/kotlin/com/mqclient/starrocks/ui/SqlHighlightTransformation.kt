package com.mqclient.starrocks.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * SQL 语法高亮（SQL 控制台编辑器专用）：关键字 / 字符串字面量 / 数字 / 注释分色。
 *
 * 纯视觉转换 —— 只附加 [SpanStyle]，不改文本内容；offset mapping 是
 * [OffsetMapping.Identity]，光标移动与选区逻辑完全不受影响（字符数不变）。
 * 编辑器侧直接把本类传给 `OutlinedTextField(visualTransformation = …)`。
 *
 * 与 mq-ui 的 `MqscSyntaxHighlighter` 同族但多一层**区间占用**处理：SQL 字符串
 * 字面量里出现 `'SELECT x'` 这类内容太常见，关键字正则必须跳过已被字符串占用的
 * 区间，否则字面量内外两层染色互相覆盖。字符串 / 注释优先占用，关键字 / 数字
 * 后扫并跳过重叠区间。
 *
 * 配色可注入：SQL 控制台在组合期用 `MaterialTheme` 取色构造（关键字=primary、
 * 字符串=secondary、数字=tertiary）；默认值是 VS Code Dark+ 惯用色（深浅主题
 * 都可读），供独立预览 / 测试使用。
 *
 * 正则全部预编译为伴生对象常量：`filter` 在每次按键都会被调用，是输入热路径。
 */
class SqlHighlightTransformation(
    private val keywordColor: Color = Color(0xFF569CD6),
    private val stringColor: Color = Color(0xFFCE9178),
    private val numberColor: Color = Color(0xFFB5CEA8),
    private val commentColor: Color = Color(0xFF6A9955),
) : VisualTransformation {

    private val keywordStyle = SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)
    private val stringStyle = SpanStyle(color = stringColor)
    private val numberStyle = SpanStyle(color = numberColor)
    private val commentStyle = SpanStyle(color = commentColor)

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val ranges = mutableListOf<StyleRange>()

        // 1) 字符串字面量：最先占用区间（含未闭合的字面量 —— 打字中间态也染色）。
        STRING_REGEX.findAll(source).forEach { match ->
            ranges += StyleRange(match.range.first, match.range.last + 1, stringStyle)
        }

        // 2) 行注释：不落入字符串区间才染。
        COMMENT_REGEX.findAll(source).forEach { match ->
            if (ranges.none { it.overlaps(match.range) }) {
                ranges += StyleRange(match.range.first, match.range.last + 1, commentStyle)
            }
        }

        // 3) 关键字 / 数字：跳过已被占用（字符串 / 注释）的区间。
        KEYWORD_REGEX.findAll(source).forEach { match ->
            if (ranges.none { it.overlaps(match.range) }) {
                ranges += StyleRange(match.range.first, match.range.last + 1, keywordStyle)
            }
        }
        NUMBER_REGEX.findAll(source).forEach { match ->
            if (ranges.none { it.overlaps(match.range) }) {
                ranges += StyleRange(match.range.first, match.range.last + 1, numberStyle)
            }
        }

        val annotated = buildAnnotatedString {
            append(source)
            ranges.sortedBy { it.start }.forEach { addStyle(it.style, it.start, it.end) }
        }
        // 字符数与输入完全一致 → Identity mapping，光标 / 选区 / 拖选零影响。
        return TransformedText(annotated, OffsetMapping.Identity)
    }

    private data class StyleRange(val start: Int, val end: Int, val style: SpanStyle) {
        fun overlaps(range: IntRange): Boolean = start < range.last + 1 && end > range.first
    }

    private companion object {
        // 多词关键字放前面：正则 alternation 从左到右，长的先命中才不会被 JOIN 截断。
        val KEYWORD_REGEX = Regex(
            """\b(?:SELECT|FROM|WHERE|GROUP\s+BY|ORDER\s+BY|LEFT\s+OUTER\s+JOIN|LEFT\s+JOIN|""" +
                """RIGHT\s+OUTER\s+JOIN|RIGHT\s+JOIN|INNER\s+JOIN|FULL\s+JOIN|CROSS\s+JOIN|JOIN|""" +
                """LIMIT|OFFSET|HAVING|UNION|DISTINCT|INSERT|UPDATE|DELETE|CREATE|EXPLAIN|""" +
                """ANALYZE|SHOW|KILL|AND|OR|NOT|AS|ON|IN|IS|NULL|CASE|WHEN|THEN|ELSE|END|""" +
                """ASC|DESC|COUNT|SUM|AVG|MIN|MAX)\b""",
            RegexOption.IGNORE_CASE,
        )
        // 字符串字面量：'' 是 SQL 的引号转义；尾部的 ? 兼容打字中的未闭合字面量。
        val STRING_REGEX = Regex("""'(?:[^']|'')*'?'?""")
        val NUMBER_REGEX = Regex("""\b\d+(?:\.\d+)?\b""")
        val COMMENT_REGEX = Regex("""--[^\n]*""")
    }
}
