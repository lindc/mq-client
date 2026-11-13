package com.mqclient.starrocks.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** CSV 转义守卫（RFC 4180）：逗号 / 引号 / 换行必须双引号包裹，内部引号双写。 */
class StarRocksCsvExportTest {

    @Test
    fun `plain values pass through and null becomes empty`() {
        assertEquals("42", csvField("42"))
        assertEquals("abc", csvField("abc"))
        assertEquals("", csvField(null))
        assertEquals("", csvField(""))
    }

    @Test
    fun `special characters are quoted and inner quotes doubled`() {
        assertEquals("\"a,b\"", csvField("a,b"), "逗号")
        assertEquals("\"a\"\"b\"", csvField("a\"b"), "引号双写")
        assertEquals("\"a\nb\"", csvField("a\nb"), "换行")
        assertEquals("\"a\r\nb\"", csvField("a\r\nb"), "回车换行")
        assertEquals("\"a,b\nc\"", csvField("a,b\nc"), "多重特殊字符一次包裹")
    }

    @Test
    fun `csv line joins cells with escaped fields`() {
        assertEquals("1,abc,", csvLine(listOf(1L, "abc", null)))
        assertEquals("\"x,y\",\"he said \"\"hi\"\"\"", csvLine(listOf("x,y", "he said \"hi\"")))
        assertEquals("NULL-free,row", csvLine(listOf("NULL-free", "row")))
    }
}
