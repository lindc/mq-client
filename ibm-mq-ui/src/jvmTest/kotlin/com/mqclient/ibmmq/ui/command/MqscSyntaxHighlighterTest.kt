package com.mqclient.ibmmq.ui.command

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 语法高亮的**核心不变量**：只加样式，绝不改文本。
 *
 * 这条以前没有任何测试。它一旦破了（比如漏了 `append(text.text)`、或者按正则重组文本），
 * 用户看到的就是编辑器内容与实际发送的命令不一致 —— 在会 `DELETE` 对象的控制台里，
 * 这属于会伤人的 bug。
 */
class MqscSyntaxHighlighterTest {

    private val highlighter = MqscSyntaxHighlighter()

    private fun highlight(text: String): AnnotatedString = highlighter.highlight(AnnotatedString(text))

    @Test
    fun `text content is never modified`() {
        val source = "DISPLAY QLOCAL(ORDER.Q) CURDEPTH\n* comment\nDELETE QLOCAL('X')"

        assertEquals(source, highlight(source).text)
    }

    @Test
    fun `verbs are bolded`() {
        val result = highlight("DISPLAY QLOCAL(*)")

        val boldAt = result.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(1, boldAt.size, "只有一个动词被加粗")
        assertEquals(0, boldAt.single().start)
        assertEquals("DISPLAY".length, boldAt.single().end)
    }

    @Test
    fun `object type and string literal get their own spans`() {
        val result = highlight("DELETE QLOCAL('QUEUE')")

        assertTrue(result.spanStyles.size >= 3, "动词 + 对象类型 + 字符串：${result.spanStyles.size}")
    }

    @Test
    fun `comments are italic`() {
        val result = highlight("* this is a comment")

        assertTrue(
            result.spanStyles.any { it.item.fontStyle == FontStyle.Italic },
            "注释行应斜体：${result.spanStyles}",
        )
    }

    @Test
    fun `empty text is safe`() {
        assertEquals("", highlight("").text)
    }
}
