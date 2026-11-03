package com.mqclient.message.interpreter.json

import com.mqclient.message.interpreter.InterpreterResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JSON 格式化的行为契约：**畸形必须失败**，合法输入只动空白。
 *
 * 旧实现是纯 token 重排，`{"a":}` / `{` 也会返回 Success —— 用户会以为消息内容
 * 是合法 JSON。这里把这条钉死。
 */
class JsonInterpreterTest {

    private val interpreter = JsonInterpreter()

    private fun render(json: String, indent: Int = 2): String {
        val result = interpreter.interpret(
            json.toByteArray(Charsets.UTF_8),
            0,
            1208,
            mapOf("indent" to indent.toString()),
        )
        assertTrue(result is InterpreterResult.Success, "应能格式化，实际：$result")
        return (result as InterpreterResult.Success).formatted
    }

    private fun failure(json: String): InterpreterResult.Failure {
        val result = interpreter.interpret(json.toByteArray(Charsets.UTF_8), 0, 1208, emptyMap())
        assertTrue(result is InterpreterResult.Failure, "应报失败，实际：$result")
        return result as InterpreterResult.Failure
    }

    @Test
    fun `malformed json is reported as failure`() {
        // 这五个在旧实现里全是 Success。
        for (broken in listOf("""{"a":}""", """{"a":1,}""", "{", """{"a":1}{"b":2}""", """{a:1}""")) {
            val error = failure(broken)
            assertTrue(error.message.contains("JSON parse failed"), "『$broken』→ ${error.message}")
        }
    }

    @Test
    fun `valid json is indented`() {
        val out = render("""{"a":1,"b":[1,2]}""")

        assertEquals(
            """
            {
              "a": 1,
              "b": [
                1,
                2
              ]
            }
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `empty containers stay compact`() {
        // 不拆成 `{` / 空行 / `}` 三行 —— 那是纯视觉噪声。
        assertEquals(
            """
            {
              "a": {},
              "b": []
            }
            """.trimIndent(),
            render("""{"a":{},"b":[]}"""),
        )
    }

    @Test
    fun `string content is never rewritten`() {
        // 字符串里的花括号 / 转义引号 / unicode 转义都原样保留（只动空白）。
        val json = """{"code":"a{b}c \"q\" \u00e9","num":1.0,"big":12345678901234567890}"""
        val out = render(json)

        assertTrue(out.contains("""a{b}c \"q\" \u00e9"""), "字符串内容被改写了：\n$out")
        assertTrue(out.contains("1.0"), "数字形态被改写了：\n$out")
        assertTrue(out.contains("12345678901234567890"), "大整数精度丢失：\n$out")
    }

    @Test
    fun `canInterpret rejects text that merely starts with a brace`() {
        // Base64 解码层的链式选择依赖这条：`{oops` 不该被认领。
        assertFalse(interpreter.canInterpret("{oops".toByteArray(), 0, 1208))
        assertTrue(interpreter.canInterpret("""{"a":1}""".toByteArray(), 0, 1208))
        assertTrue(interpreter.canInterpret("""  [1,2]""".toByteArray(), 0, 1208))
        assertFalse(interpreter.canInterpret("hello".toByteArray(), 0, 1208))
    }

    @Test
    fun `indent param is honored and clamped`() {
        assertTrue(render("""{"a":1}""", indent = 4).contains("\n    \"a\""))
        // 越界值收敛到 0..8，不抛异常。
        assertTrue(render("""{"a":1}""", indent = 99).contains("\n        \"a\""))
    }

    @Test
    fun `blank content fails`() {
        failure("   ")
    }
}
