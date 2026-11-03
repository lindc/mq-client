package com.mqclient.message.interpreter.idoc

import com.mqclient.message.interpreter.InterpreterResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SAP IDoc 解释器：控制记录 + 数据段的边界行为。
 *
 * 这个解释器最容易出错的地方是**认领条件**与**畸形输入**：
 *  - 认领只看第一行是否以 `EDI_DC*` / `E2ED*` 开头（普通正文里出现 IDoc 字样不算）；
 *  - 段名带下划线（`EDI_DC40`），按 `isLetterOrDigit` 截断会认不出来；
 *  - 只有数据段、没有控制记录的文件也要能出结果（不少中间件只转储数据段）；
 *  - 未知字符集参数要报 Failure，而不是抛异常。
 */
class IdocInterpreterTest {

    private val interpreter = IdocInterpreter()

    private fun interpret(text: String, params: Map<String, String> = emptyMap()): InterpreterResult =
        interpreter.interpret(text.toByteArray(Charsets.UTF_8), 0, 1208, params)

    private fun success(text: String, params: Map<String, String> = emptyMap()): String {
        val result = interpret(text, params)
        assertTrue(result is InterpreterResult.Success, "actual: $result")
        return (result as InterpreterResult.Success).formatted
    }

    /**
     * 控制记录用 TAB 分隔（文件端口最常见的形态），字段顺序与解析器的
     * CONTROL_FIELDS 前若干位对齐：TABNAM / MANDT / DOCNUM / DOCREL / STATUS /
     * DIRECT / OUTMOD / TEST / IDOCTYP / CIMTYP / MESTYP / …
     */
    private val control = listOf(
        "EDI_DC40", "80", "0000000000123456", "731", "53", "2", "ORDERS", "ORDERS05",
        "ORDERS05", "", "ORDERS", "ORDRSP", "0000000000000001", "SAP", "640",
        "LS", "", "SAPPRD", "", "", "", "LS", "", "ERP000", "", "", "",
    ).joinToString("\t")
    private val item = "E2EDP01001  0000000000123456 000000002 000000001 02  item data"

    @Test
    fun `claims control record and data segments`() {
        assertTrue(interpreter.canInterpret(control.toByteArray(), 0, 1208))
        assertTrue(interpreter.canInterpret(item.toByteArray(), 0, 1208))
    }

    @Test
    fun `does not claim text that merely mentions idoc`() {
        // 行首匹配：正文里出现 E2ED 字样但不在行首，不算。
        assertFalse(interpreter.canInterpret("hello E2EDP01001 world".toByteArray(), 0, 1208))
        assertFalse(interpreter.canInterpret("".toByteArray(), 0, 1208))
        assertFalse(interpreter.canInterpret("   \n  \n".toByteArray(), 0, 1208))
    }

    @Test
    fun `leading blank lines do not break claiming`() {
        val content = "\n\n  $control\n"

        assertTrue(interpreter.canInterpret(content.toByteArray(), 0, 1208))
        assertTrue(success(content).contains("EDI_DC40"))
    }

    @Test
    fun `control record fields are labelled in SAP order`() {
        val out = success("$control\n$item")

        assertTrue(out.contains("TABNAM = EDI_DC40"), out)
        assertTrue(out.contains("MANDT = 80"), out)
        assertTrue(out.contains("DOCNUM = 0000000000123456"), out)
        assertTrue(out.contains("IDOCTYP = ORDERS05"), out)
        assertTrue(out.contains("MESTYP = ORDERS"), out)
        assertTrue(out.contains("SNDPRN = SAPPRD"), out)
    }

    @Test
    fun `segment columns are parsed and indexed`() {
        val out = success("$control\n$item")

        // [段号] 段名 (doc=…, parent=…, level=…)
        assertTrue(out.contains("[000000002] E2EDP01001"), out)
        assertTrue(out.contains("doc=0000000000123456"), out)
        assertTrue(out.contains("parent=000000001"), out)
        assertTrue(out.contains("level=2"), out)
        assertTrue(out.contains("item data"), out)
    }

    @Test
    fun `data only file is still parsed`() {
        // 只有数据段（没有控制记录）不应报错 —— 首行是 E2ED 就足以认领。
        val out = success(item)

        assertTrue(out.contains("E2EDP01001"), out)
        assertTrue(out.contains("segment(s)"), out)
    }

    @Test
    fun `non idoc content fails instead of throwing`() {
        val result = interpret("hello world\nplain text")

        assertTrue(result is InterpreterResult.Failure, "actual: $result")
        assertTrue((result as InterpreterResult.Failure).message.contains("not an IDoc"))
    }

    @Test
    fun `empty content fails`() {
        assertTrue(interpret("") is InterpreterResult.Failure)
        assertTrue(interpret("   \n\n") is InterpreterResult.Failure)
    }

    @Test
    fun `unknown charset parameter fails with a readable message`() {
        val result = interpret(control, mapOf("charset" to "NO-SUCH-CHARSET"))

        assertTrue(result is InterpreterResult.Failure, "actual: $result")
        assertTrue((result as InterpreterResult.Failure).message.contains("unknown charset"))
    }

    @Test
    fun `show raw segments returns the original lines`() {
        val text = "$control\n$item"
        val out = success(text, mapOf("showRawSegments" to "true"))

        assertEquals(text, out)
    }

    @Test
    fun `version is inferred from the control record name`() {
        assertTrue(success(control).contains("IDoc 3.x"), "EDI_DC40 → 3.x")
        assertTrue(success(control.replace("EDI_DC40", "EDI_DC20")).contains("IDoc 2.x"))
    }

    @Test
    fun `hierarchy indents nested segments when expand is on`() {
        val nested = "E2EDK01001  0000000000123456 000000003 000000002 03  child"
        val expanded = success("$control\n$item\n$nested")
        val flat = success("$control\n$item\n$nested", mapOf("expandNested" to "false"))

        // level=3 → 展开时 4 空格缩进；关闭展开则平铺在行首。
        assertTrue(expanded.contains("\n    [000000003]"), "expanded: $expanded")
        assertTrue(flat.contains("\n[000000003]"), "flat: $flat")
    }
}
