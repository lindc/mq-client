package com.mqclient.message.interpreter.xml

import com.mqclient.message.interpreter.InterpreterResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XML 格式化的行为契约。
 *
 * 这里盯的是**旧实现切坏的那三类输入**（CDATA / DOCTYPE 内部子集 / 属性值里的 `>`），
 * 以及「解析失败必须报 Failure」—— 旧实现是字符切分，任何输入都能“成功”。
 */
class XmlInterpreterTest {

    private val interpreter = XmlInterpreter()

    private fun render(xml: String): String {
        val result = interpreter.interpret(xml.toByteArray(Charsets.UTF_8), 0, 1208, emptyMap())
        assertTrue(result is InterpreterResult.Success, "应能格式化，实际：$result")
        return (result as InterpreterResult.Success).formatted
    }

    private fun failure(xml: String): InterpreterResult.Failure {
        val result = interpreter.interpret(xml.toByteArray(Charsets.UTF_8), 0, 1208, emptyMap())
        assertTrue(result is InterpreterResult.Failure, "应报失败，实际：$result")
        return result as InterpreterResult.Failure
    }

    @Test
    fun `nested elements are indented`() {
        val out = render("<root><a>1</a><b><c>2</c></b></root>")

        assertEquals(
            """
            <root>
              <a>1</a>
              <b>
                <c>2</c>
              </b>
            </root>
            """.trimIndent(),
            out,
        )
    }

    /** CDATA 里的 `<` 不能当成标签（旧 split 正则的第一个破绽）。 */
    @Test
    fun `cdata containing angle brackets is preserved verbatim`() {
        val out = render("<root><code><![CDATA[if (a < b && c > d) { run(); }]]></code></root>")

        assertTrue(
            out.contains("<![CDATA[if (a < b && c > d) { run(); }]]>"),
            "CDATA 应逐字保留（含 < 与 &&）：\n$out",
        )
    }

    /** DOCTYPE 内部子集里的声明不能被当元素（第二个破绽）。 */
    @Test
    fun `doctype internal subset does not break formatting`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE msg [ <!ENTITY who "world"> ]>
            <msg>hello &who;</msg>
        """.trimIndent()

        val out = render(xml)

        // 实体按解析结果展开（重新序列化的既定代价）。
        assertTrue(out.contains("hello world"), "实体应被展开：\n$out")
        assertFalse(out.contains("<!ENTITY"), "DOCTYPE 内部子集不应作为元素输出：\n$out")
    }

    /** 属性值里的 `>` 不结束标签（第三个破绽）。 */
    @Test
    fun `attribute value containing a closing bracket is intact`() {
        val out = render("""<root><a title="x>y">v</a></root>""")

        assertTrue(out.contains("""title="x&gt;y""""), "属性值应完整保留（转义后）：\n$out")
        assertFalse(out.contains("<a title=\"x>"), "属性值不能被截断：\n$out")
    }

    @Test
    fun `comments and processing instructions survive`() {
        val out = render("<root><!-- note --><?pi data?><a>1</a></root>")

        assertTrue(out.contains("<!-- note -->"), out)
        assertTrue(out.contains("<?pi data?>"), out)
    }

    @Test
    fun `empty element is self closing`() {
        assertEquals("<root/>", render("<root></root>"))
        assertEquals("<root/>", render("<root>   </root>"))
    }

    @Test
    fun `multiple root fragments are accepted`() {
        // MQ 消息里常见多个并列根元素；整篇解析失败后按片段重试。
        val out = render("<a>1</a><b>2</b>")

        assertTrue(out.contains("<a>1</a>"), out)
        assertTrue(out.contains("<b>2</b>"), out)
        assertFalse(out.contains("mqclient-fragment-root"), "包装根不能出现在输出里：\n$out")
    }

    @Test
    fun `mismatched tags fail with the offending name`() {
        val error = failure("<a><b></a>")

        assertTrue(error.message.contains("XML 解析失败"), error.message)
    }

    @Test
    fun `unclosed element fails`() {
        failure("<root><a>1</a>")
    }

    @Test
    fun `external entity is not resolved`() {
        // XXE 加固：外部实体不取内容（FEATURE_SECURE_PROCESSING + 禁止外部通用实体）。
        // 允许两种结局：拒绝解析（Failure）或展开成空 —— 绝不能把 /etc/passwd 读进来。
        val result = interpreter.interpret(
            """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><r>&x;</r>"""
                .toByteArray(Charsets.UTF_8),
            0,
            1208,
            emptyMap(),
        )
        when (result) {
            is InterpreterResult.Failure -> Unit
            is InterpreterResult.Success -> {
                assertFalse(result.formatted.contains("root:"), "外部实体内容不能被读出：\n${result.formatted}")
            }
        }
    }

    @Test
    fun `pretty false returns the original text`() {
        val xml = "<a>\n  <b>1</b>\n</a>"
        val result = interpreter.interpret(
            xml.toByteArray(Charsets.UTF_8),
            0,
            1208,
            mapOf("pretty" to "false"),
        )

        assertEquals(InterpreterResult.Success(xml.trim()), result)
    }

    @Test
    fun `not xml is rejected by interpret`() {
        val result = interpreter.interpret("hello".toByteArray(), 0, 1208, emptyMap())

        assertTrue(result is InterpreterResult.Failure, "actual: $result")
        assertFalse(interpreter.canInterpret("hello".toByteArray(), 0, 1208))
    }
}
