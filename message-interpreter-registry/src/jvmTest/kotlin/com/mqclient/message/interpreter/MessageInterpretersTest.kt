package com.mqclient.message.interpreter

import com.mqclient.message.interpreter.base64.Base64Interpreter
import com.mqclient.message.interpreter.idoc.IdocInterpreter
import com.mqclient.message.interpreter.json.JsonInterpreter
import com.mqclient.message.interpreter.text.TextInterpreter
import com.mqclient.message.interpreter.xml.XmlInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
