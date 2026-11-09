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

        assertTrue(error.message.contains("XML parse failed"), error.message)
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

/**
 * 消息体是**外部可控输入**，XML 解释器必须对放大与深嵌套有硬上限。
 *
 * 旧实现把五条安全特性全包在 `runCatching` 里静默吞掉，且序列化递归无深度上限：
 * 深嵌套会把栈打爆、`StackOverflowError` / `OutOfMemoryError` 都是 `Error`，
 * `catch (Exception)` 接不住，会穿出 Compose 组合作用域把界面弄死。
 */
class XmlInterpreterHardeningTest {

    private val interpreter = XmlInterpreter()

    private fun interpret(xml: String): InterpreterResult =
        interpreter.interpret(xml.toByteArray(Charsets.UTF_8), 0, 1208, emptyMap())

    /** billion-laughs：实体展开必须被上限拦住（而不是把堆吃光）。 */
    @Test
    fun `entity expansion bomb is rejected`() {
        val sb = StringBuilder("""<?xml version="1.0"?><!DOCTYPE lolz [""")
        sb.append("""<!ENTITY lol0 "lol">""")
        for (i in 1..9) {
            sb.append("<!ENTITY lol").append(i).append(" \"")
            repeat(10) { sb.append("&lol").append(i - 1).append(';') }
            sb.append("\">")
        }
        sb.append("]><lolz>&lol9;</lolz>")

        val result = interpret(sb.toString())

        assertTrue(result is InterpreterResult.Failure, "实体炸弹应被拒绝，实际：$result")
    }

    /** 超过深度上限的嵌套报 Failure，不把栈打爆。 */
    @Test
    fun `excessively deep nesting fails instead of overflowing the stack`() {
        val depth = 400
        val xml = buildString {
            repeat(depth) { append("<a>") }
            append("x")
            repeat(depth) { append("</a>") }
        }

        val result = interpret(xml)

        assertTrue(result is InterpreterResult.Failure, "超深嵌套应报失败，实际：$result")
        assertTrue(
            result.message.orEmpty().contains("deeper than"),
            "错误文案应说明深度超限：${result.message}",
        )
    }

    /** 正常深度仍然可用（上限不能误伤常规报文）。 */
    @Test
    fun `nesting below the cap still formats`() {
        val depth = 50
        val xml = buildString {
            repeat(depth) { append("<a>") }
            append("x")
            repeat(depth) { append("</a>") }
        }

        assertTrue(interpret(xml) is InterpreterResult.Success)
    }
}
