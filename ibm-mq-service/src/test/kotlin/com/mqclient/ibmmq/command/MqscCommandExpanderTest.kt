package com.mqclient.ibmmq.command

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [MqscCommandExpander] 的用例锁定：动词 / 对象类型缩写、带引号值、全称不变、
 * 注释跳过、未知词保留。服务端对参数区大小写不敏感，第三词起不规范化是刻意行为。
 */
class MqscCommandExpanderTest {

    @Test
    fun expandsVerbAndObjectAbbreviations() {
        assertEquals("DISPLAY QLOCAL(*)", MqscCommandExpander.expand("dis q(*)"))
        assertEquals("DISPLAY QLOCAL('QUEUE')", MqscCommandExpander.expand("DIS Q('QUEUE')"))
        assertEquals("DISPLAY QLOCAL(*)", MqscCommandExpander.expand("dis ql(*)"))
        assertEquals("DEFINE QLOCAL(Q1) MAXDEPTH(5000)", MqscCommandExpander.expand("def ql(Q1) MAXDEPTH(5000)"))
        assertEquals("DELETE QLOCAL(Q1)", MqscCommandExpander.expand("del q(Q1)"))
        assertEquals("DISPLAY CHANNEL(*)", MqscCommandExpander.expand("dis chl(*)"))
        assertEquals("DISPLAY CHANNEL(*)", MqscCommandExpander.expand("dis ch(*)"))
    }

    @Test
    fun fullWordsPassThroughUppercased() {
        assertEquals("DISPLAY QLOCAL(*)", MqscCommandExpander.expand("DISPLAY QLOCAL(*)"))
        assertEquals("PING QMGR", MqscCommandExpander.expand("ping qmgr"))
        assertEquals("DISPLAY QMGR CMDSERV", MqscCommandExpander.expand("display qmgr CMDSERV"))
    }

    @Test
    fun unknownWordsStayUntouched() {
        // 未知动词 / 对象类型整条原样返回（第一词未命中即放弃）。
        assertEquals("frob q(*)", MqscCommandExpander.expand("frob q(*)"))
        // 动词命中、对象未知：动词仍展开，对象保留。
        assertEquals("DISPLAY foo", MqscCommandExpander.expand("dis foo"))
    }

    @Test
    fun ambiguousAbbreviationPrefersReset() {
        assertEquals("RESET QMGR", MqscCommandExpander.expand("res qmgr"))
    }

    @Test
    fun commentsAndBlankLinesAreUntouched() {
        assertEquals("* 这是注释", MqscCommandExpander.expand("* 这是注释"))
        assertEquals("  * dis q(*)", MqscCommandExpander.expand("  * dis q(*)"))
        assertEquals("", MqscCommandExpander.expand(""))
    }

    @Test
    fun parameterAreaIsNeverTouched() {
        assertEquals(
            "DISPLAY QLOCAL('QUEUE') curdepth",
            MqscCommandExpander.expand("dis q('QUEUE') curdepth"),
        )
        // 引号里的词不会被当关键词（前两词在命令开头，引号区只出现在参数区）。
        assertEquals(
            "DISPLAY QLOCAL('DIS Q')",
            MqscCommandExpander.expand("dis q('DIS Q')"),
        )
    }

    @Test
    fun leadingWhitespaceIsPreserved() {
        assertEquals("  DISPLAY QLOCAL(*)", MqscCommandExpander.expand("  dis q(*)"))
    }
}
