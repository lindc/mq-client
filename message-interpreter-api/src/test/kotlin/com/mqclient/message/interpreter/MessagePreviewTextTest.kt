package com.mqclient.message.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 列表预览文本的生成规则（[previewTextOf] / [singleLinePreview]）。
 *
 * 背景：`Text(maxLines = 1)` 只画第一行，原预览函数原样返回解码结果 —— 正文以
 * 换行 / 空格 / UTF-8 BOM 开头时首行是空的，单元格一个像素都不画（现场表现：
 * 同一批 XML 消息里一条正常、另一条整格空白）。这组用例钉住「预览永远以可见
 * 字符开头」这条不变量。
 */
class MessagePreviewTextTest {

    private fun previewOf(text: String) = previewTextOf(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `leading newlines are stripped so the first line is visible`() {
        assertEquals(
            "<wpf:ResourceDictionary xmlns:wpf=\"x\"/>",
            previewOf("\n\n<wpf:ResourceDictionary xmlns:wpf=\"x\"/>"),
        )
        assertEquals("hello", previewOf("\r\n\r\nhello"))
    }

    @Test
    fun `utf8 bom and zero width characters are dropped`() {
        // .NET 侧写出的 UTF-8 文件常带 BOM；BOM 不可见，留着就会让首行"看起来是空的"。
        assertEquals("<wpf:ResourceDictionary/>", previewOf("\uFEFF<wpf:ResourceDictionary/>"))
        assertEquals("abc", previewOf("\u200B\u200C\u200D\u2060abc"))
    }

    @Test
    fun `newlines between content collapse to a single space`() {
        // 折叠而不是删除：`</a>\n<b>` 删成 `</a><b>` 就看不出原来有换行了。
        assertEquals("</a> <b>", previewOf("</a>\n<b>"))
        assertEquals("a b c", previewOf("a \t\n\r\n b \u0007 c"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("payload", previewOf("   \n\t payload \n\t  "))
    }

    @Test
    fun `completely invisible payload normalizes to empty`() {
        // 全空白 / 全零宽 → 空串。UI 侧据此回落到「[不可见内容] + hex」，不留白。
        assertEquals("", previewOf("\n\r\n\t   "))
        assertEquals("", previewOf("\uFEFF\uFEFF"))
        assertEquals("", previewTextOf(ByteArray(0)))
    }

    @Test
    fun `invalid utf8 bytes stay visible as replacement characters`() {
        // 容错解码的替换符是有字形的 —— 这条路径本来就不会"空白"，不该被当成根因。
        val preview = previewTextOf(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        assertTrue(preview.isNotBlank(), "非法字节必须产出可见的替换符，实测=$preview")
        assertEquals('\uFFFD', preview.first())
    }

    @Test
    fun `preview is truncated to the byte budget before normalizing`() {
        val text = "x".repeat(1000)
        assertEquals(DEFAULT_PREVIEW_BYTES, previewTextOf(text.toByteArray(Charsets.UTF_8)).length)
        assertEquals(10, previewTextOf(text.toByteArray(Charsets.UTF_8), maxBytes = 10).length)
    }

    @Test
    fun `plain text is left untouched`() {
        assertEquals("order-42 created", previewOf("order-42 created"))
        assertEquals("订单已创建", previewOf("订单已创建"))
    }

    @Test
    fun `bom only payload is empty but a bom plus text keeps the text`() {
        assertEquals("", singleLinePreview("\uFEFF"))
        assertEquals("x", singleLinePreview("\uFEFFx"))
    }
}
