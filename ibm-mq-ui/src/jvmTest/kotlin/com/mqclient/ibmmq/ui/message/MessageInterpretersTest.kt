package com.mqclient.ibmmq.ui.message

import com.mqclient.message.interpreter.DecodingInterpreter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 解释器模块化机制的验证（SPI 拆分后）：ServiceLoader 必须能从 ibm-mq-ui +
 * 独立解释器模块（json / base64）的 classpath 上发现全部内置实现，排序稳定，
 * 且回落语义正确。
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
