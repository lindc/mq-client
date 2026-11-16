package com.mqclient.starrocks.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SQL 语法高亮守卫：文本零改动、offset 不漂移、区间不重叠染错。 */
class SqlHighlightTransformationTest {

    private val transformation = SqlHighlightTransformation()
    private val defaultStringColor = Color(0xFFCE9178)

    /** 是否存在 span **完整覆盖** [start, end)。 */
    private fun coveredBy(transformed: TransformedText, start: Int, end: Int): Boolean =
        spansOf(transformed).any { it.start <= start && it.end >= end }

    /** 与 [start, end) 相交的 span 列表。 */
    private fun spansIntersecting(transformed: TransformedText, start: Int, end: Int): List<SpanStyle> =
        spansOf(transformed).filter { it.start < end && it.end > start }.map { it.item }

    private fun spansOf(transformed: TransformedText): List<AnnotatedString.Range<SpanStyle>> =
        transformed.text.spanStyles

    @Test
    fun `text content is untouched and offsets stay identical`() {
        val input = "SELECT * FROM t WHERE x = 1"
        val transformed = transformation.filter(AnnotatedString(input))
        assertEquals(input, transformed.text.text, "高亮不得增删任何字符")
        assertTrue(transformed.offsetMapping === OffsetMapping.Identity, "必须用 Identity mapping，光标 / 选区零漂移")
    }

    @Test
    fun `keywords are highlighted case-insensitively`() {
        val transformed = transformation.filter(AnnotatedString("select * from T where x = 1"))
        // s0..t5 / f9..m12 / w16..e20
        assertTrue(coveredBy(transformed, 0, 6), "select（小写）应染色")
        assertTrue(coveredBy(transformed, 9, 13), "from 应染色")
        assertTrue(coveredBy(transformed, 16, 21), "where 应染色")
    }

    @Test
    fun `keywords inside string literals are not highlighted`() {
        // 字面量 [29,39)；内部 [31,36) 只允许出现字符串色，不得混入关键字样式。
        val transformed = transformation.filter(AnnotatedString("SELECT * FROM t WHERE name = 'SELECT x'"))
        assertTrue(coveredBy(transformed, 29, 39), "字符串字面量整体染字符串色")
        val stylesInside = spansIntersecting(transformed, 31, 36).distinct()
        assertEquals(
            listOf(SpanStyle(color = defaultStringColor)),
            stylesInside,
            "字面量内部只能有字符串样式，不得被关键字色覆盖",
        )
    }

    @Test
    fun `numbers and comments get their own styles`() {
        val transformed = transformation.filter(AnnotatedString("SELECT 42 -- total"))
        assertTrue(coveredBy(transformed, 7, 9), "数字染色")
        assertTrue(coveredBy(transformed, 10, 18), "注释染色")
    }

    @Test
    fun `multi word keywords match as one span`() {
        val transformed = transformation.filter(AnnotatedString("select * from t group   by x order by y limit 10"))
        assertTrue(coveredBy(transformed, 16, 26), "group   by（多空格）应整体染色")
        assertTrue(coveredBy(transformed, 29, 37), "order by 应整体染色")
        assertTrue(coveredBy(transformed, 40, 45), "limit 应染色")
    }

    @Test
    fun `empty and unmatched input produce no spans`() {
        val transformed = transformation.filter(AnnotatedString("abc def"))
        assertTrue(spansOf(transformed).isEmpty())
        assertTrue(spansOf(transformation.filter(AnnotatedString(""))).isEmpty())
    }
}
