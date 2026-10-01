package com.mqclient.message.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 解释器模块化机制的验证：ServiceLoader 必须能从 registry 的 classpath 上发现
 * 全部内置实现（各解释器独立模块经聚合依赖进入），排序稳定，回落语义正确。
 */
class MessageInterpretersTest {

    @Test
    fun `loads all five built-in interpreters`() {
        val ids = MessageInterpreters.loadAll().map { it.id }
        assertEquals(listOf("text", "json", "xml", "base64", "idoc"), ids)
    }

    @Test
    fun `results are sorted by sortOrder`() {
        val all = MessageInterpreters.loadAll()
        assertEquals(all.sortedBy { it.sortOrder }, all)
    }

    @Test
    fun `byId falls back to text for unknown id`() {
        assertEquals("text", MessageInterpreters.byId("no_such_interpreter").id)
    }

    @Test
    fun `only base64 is a decoding interpreter`() {
        val decoders = MessageInterpreters.loadAll().filterIsInstance<DecodingInterpreter>()
        assertEquals(listOf("base64"), decoders.map { it.id })
    }

    @Test
    fun `idoc claims and parses a flat idoc file`() {
        val idoc = MessageInterpreters.byId("idoc")
        val sample = """
            EDI_DC40  1000000000123456  30  ORDERS.ORDERS05  ORDERS  LS  SAPPRD  ERP000
            E2EDK01001  0000000000123456 000000001 000000000 01  header data
            E2EDP01001  0000000000123456 000000002 000000001 02  item data
        """.trimIndent()
        val content = sample.toByteArray(Charsets.UTF_8)
        assertTrue(idoc.canInterpret(content, 0, 1208))
        val result = idoc.interpret(content, 0, 1208, emptyMap())
        assertTrue(result is InterpreterResult.Success, "actual: $result")
        val text = (result as InterpreterResult.Success).formatted
        assertTrue(text.contains("ORDERS.ORDERS05"))
        assertTrue(text.contains("E2EDK01001"))
        assertTrue(text.contains("[000000001]"))
    }

    @Test
    fun `idoc does not claim plain text`() {
        val idoc = MessageInterpreters.byId("idoc")
        val content = "hello world\nthis is not an idoc".toByteArray()
        assertEquals(false, idoc.canInterpret(content, 0, 1208))
        val result = idoc.interpret(content, 0, 1208, emptyMap())
        assertTrue(result is InterpreterResult.Failure)
    }
}
