package com.mqclient.starrocks.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 「选中即执行」的回归守卫（不依赖 Compose 画布 / 数据库）：
 * 钉住 [selectedSource] 与 [splitStatements] 的取源语义 —— 选中第一行时，
 * 第二行的语法错误绝不能被带进执行列表。
 */
class StarRocksSqlSelectionTest {

    private val script = "SELECT 1;\nSELEC 2;\nSELECT 3;"

    @Test
    fun `selection extracts only the highlighted text`() {
        val editor = TextFieldValue(script, selection = TextRange(0, 9)) // "SELECT 1;"
        val selected = selectedSource(editor)
        assertEquals("SELECT 1;", selected)
        assertEquals(listOf("SELECT 1"), splitStatements(selected!!), "只应执行选中这一条")
    }

    @Test
    fun `reverse selection is normalized with min max`() {
        val editor = TextFieldValue(script, selection = TextRange(9, 0))
        assertEquals("SELECT 1;", selectedSource(editor), "反向选择不得越界 / 抛异常")
    }

    @Test
    fun `collapsed caret falls back to full text`() {
        val editor = TextFieldValue(script, selection = TextRange(5))
        assertNull(selectedSource(editor))
        assertEquals(listOf("SELECT 1", "SELEC 2", "SELECT 3"), splitStatements(editor.text))
    }

    @Test
    fun `splitStatements drops trailing semicolon comments and empty segments`() {
        assertEquals(
            listOf("SELECT 1", "SELECT 2"),
            splitStatements("SELECT 1;\n-- a comment\nSELECT 2;;\n"),
        )
        assertEquals(emptyList(), splitStatements(" ; ; \n-- only comment\n"))
    }

    @Test
    fun `selection is not contaminated by the rest of the editor`() {
        // 复现用户报告的场景：只选中第一行（不含第二行的语法错误）。
        val editor = TextFieldValue("SELECT 1;\nSELEC 2;", selection = TextRange(0, 8))
        val statements = splitStatements(selectedSource(editor)!!)
        assertEquals(listOf("SELECT 1"), statements)
        assertEquals(false, statements.any { it.contains("SELEC 2") })
    }

    @Test
    fun `re-selecting a different line yields only the new selection`() {
        // 回归守卫：先选第 2 行、再选第 1 行（或反之），取源必须只看当前
        // TextFieldValue 的 selection —— 绝不能残留上一次的旧选区。
        val text = "SELECT 1;\nSELECT 2;"
        val secondLine = TextFieldValue(text, selection = TextRange(10, 19))
        assertEquals("SELECT 2;", selectedSource(secondLine))

        val reselectedFirstLine = TextFieldValue(text, selection = TextRange(0, 9))
        assertEquals("SELECT 1;", selectedSource(reselectedFirstLine))
        assertEquals(
            listOf("SELECT 1"),
            splitStatements(selectedSource(reselectedFirstLine)!!),
            "重新选第 1 行后，执行源不得仍是第 2 行",
        )
    }
}
