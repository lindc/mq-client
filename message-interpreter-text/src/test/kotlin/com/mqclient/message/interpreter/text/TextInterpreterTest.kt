package com.mqclient.message.interpreter.text

import com.mqclient.message.interpreter.InterpreterResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 文本解释器：默认解释器（sortOrder 最小），**永不被 canInterpret 推荐**，
 * 但 interpret 必须能把任意字节按 CCSID 解出来、且不抛异常。
 *
 * 作为全链路兜底，它的失败等于界面白屏 —— 所以边界（空字节、非法序列、未知 CCSID、
 * GBK）都要覆盖。
 */
class TextInterpreterTest {

    private val interpreter = TextInterpreter()

    private fun render(bytes: ByteArray, ccsid: Int = 1208): InterpreterResult =
        interpreter.interpret(bytes, 0, ccsid, emptyMap())

    @Test
    fun `never auto claims`() {
        // 它是默认选项而不是「推荐」：canInterpret 恒 false，避免抢走 JSON/XML 的角标。
        assertFalse(interpreter.canInterpret("hello".toByteArray(), 0, 1208))
        assertFalse(interpreter.canInterpret("""{"a":1}""".toByteArray(), 0, 1208))
        assertFalse(interpreter.canInterpret(ByteArray(0), 0, 1208))
    }

    @Test
    fun `decodes utf8 as is`() {
        val result = render("消息 body".toByteArray(Charsets.UTF_8))

        assertEquals(InterpreterResult.Success("消息 body"), result)
    }

    @Test
    fun `honours the coded character set id`() {
        // 936 = GBK：同一段字节按 UTF-8 解会是乱码，按 CCSID 解才对。
        val bytes = "中文内容".toByteArray(charset("GBK"))

        assertEquals(InterpreterResult.Success("中文内容"), render(bytes, ccsid = 936))
    }

    @Test
    fun `unknown ccsid falls back to utf8 instead of throwing`() {
        val result = render("fallback".toByteArray(), ccsid = 99999)

        assertEquals(InterpreterResult.Success("fallback"), result)
    }

    @Test
    fun `invalid byte sequence is replaced not thrown`() {
        // 0xFF 在 UTF-8 里非法：必须得到替换符，而不是异常（否则详情页白屏）。
        val result = render(byteArrayOf(0x41, 0xFF.toByte(), 0x42))

        assertTrue(result is InterpreterResult.Success, "actual: $result")
        val text = (result as InterpreterResult.Success).formatted
        assertTrue(text.startsWith("A"), text)
        assertTrue(text.endsWith("B"), text)
    }

    @Test
    fun `empty content is an empty success`() {
        assertEquals(InterpreterResult.Success(""), render(ByteArray(0)))
    }
}
