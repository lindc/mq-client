package com.mqclient.message.interpreter.base64

import com.mqclient.message.interpreter.InterpreterResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Base64 解释器的认领与解码契约。
 *
 * 两个方向都要守：
 *  - 真 Base64 要能解（含 MIME 折行、无填充的 4 的倍数）；
 *  - 普通文本 / 编号**不能**被认领，更不能被“解码”成垃圾字节（旧实现用
 *    `getMimeDecoder()`，`hello, world!` 会被静默解出一段乱码）。
 */
class Base64InterpreterTest {

    private val interpreter = Base64Interpreter()

    private fun decode(text: String): InterpreterResult =
        interpreter.interpret(text.toByteArray(Charsets.UTF_8), 0, 1208, emptyMap())

    @Test
    fun `plain text is rejected by interpret`() {
        val result = decode("hello, world!")

        assertTrue(result is InterpreterResult.Failure, "普通文本不该被解码，实际：$result")
        assertTrue((result as InterpreterResult.Failure).message.contains("Base64 decode failed"))
    }

    @Test
    fun `short alphanumeric words are not auto claimed`() {
        // 数学上合法的 Base64，但在消息体里几乎总是普通词 / 编号。
        assertFalse(interpreter.canInterpret("ORDERS05".toByteArray(), 0, 1208))
        assertFalse(interpreter.canInterpret("ABCD1234".toByteArray(), 0, 1208))
        assertFalse(interpreter.canInterpret("TWFu".toByteArray(), 0, 1208))
    }

    @Test
    fun `real base64 with padding or symbols is auto claimed`() {
        val payload = "SGVsbG8sIFdvcmxkIQ==" // "Hello, World!"
        assertTrue(interpreter.canInterpret(payload.toByteArray(), 0, 1208))

        // 无填充但要够长（Base64 无填充也必须是 4 的倍数）。
        val long = "SGVsbG8sIFdvcmxk" // "Hello, World"
        assertEquals(16, long.length)
        assertTrue(interpreter.canInterpret(long.toByteArray(), 0, 1208), "长度足够长应认领")
    }

    @Test
    fun `decoding yields readable chained content`() {
        // "Hello, World!" 解码后是纯文本（无兄弟解释器认领）→ 直接展示。
        val result = decode("SGVsbG8sIFdvcmxkIQ==")

        assertTrue(result is InterpreterResult.Success, "actual: $result")
        assertEquals("Hello, World!", (result as InterpreterResult.Success).formatted)
    }

    @Test
    fun `mime line wraps are tolerated`() {
        val result = decode("SGVsbG8s\nIFdvcmxk\r\nIQ==")

        assertTrue(result is InterpreterResult.Success, "actual: $result")
        assertEquals("Hello, World!", (result as InterpreterResult.Success).formatted)
    }

    @Test
    fun `illegal characters are rejected not silently dropped`() {
        // 旧实现用 MIME 解码器会把 `,` / `!` / 空格丢掉，产出一段字节。
        for (broken in listOf("SGVsbG8s,IFdvcmxkIQ==", "SGVsbG8*IFdvcmxkIQ==", "abc$")) {
            val result = decode(broken)
            assertTrue(result is InterpreterResult.Failure, "『$broken』不该成功：$result")
        }
    }

    @Test
    fun `wrong length and misplaced padding are rejected`() {
        assertTrue(decode("SGVsbG8").let { it is InterpreterResult.Failure })   // 7 位
        assertTrue(decode("SGVsbG8=SGVz").let { it is InterpreterResult.Failure }) // 填充在中间
        assertTrue(decode("=SGV").let { it is InterpreterResult.Failure })      // 填充在开头
    }

    @Test
    fun `empty content is not claimed`() {
        assertFalse(interpreter.canInterpret("".toByteArray(), 0, 1208))
        assertFalse(interpreter.canInterpret("    ".toByteArray(), 0, 1208))
    }

    @Test
    fun `base64 of json is rendered through the json interpreter when present`() {
        // 注册表 classpath 里没有兄弟解释器时退化成纯文本 —— 两种都是 Success，
        // 所以只断言不是 Failure、且内容含原始 JSON 片段（前缀可有可无）。
        val payload = java.util.Base64.getEncoder().encodeToString("""{"a":1}""".toByteArray())
        val result = decode(payload)

        assertTrue(result is InterpreterResult.Success, "actual: $result")
        assertTrue((result as InterpreterResult.Success).formatted.contains("\"a\""))
    }
}
