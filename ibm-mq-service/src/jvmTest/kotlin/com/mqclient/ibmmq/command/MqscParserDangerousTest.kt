package com.mqclient.ibmmq.command

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 危险命令判定的安全契约。
 *
 * 背景（回归过一次的真 bug）：执行侧发出去的是 `MqscCommandExpander.expand` 的结果，
 * 而判定最初只看原始行 —— `del q(Q1)` 的原文不以 `DELETE` 开头，于是**绕过确认框**
 * 直接删队列。而 README 的缩写表恰好把 `del chl(CH1)` 当作推荐写法。
 *
 * 这组用例把「缩写必须与全称同样危险」固定下来：五个危险动词 × 缩写 / 全称 / 小写
 * 全部命中；只读命令与未知词不误报。
 */
class MqscParserDangerousTest {

    @Test
    fun `dangerous verbs - 全称与大小写都命中`() {
        listOf(
            "DELETE QLOCAL(Q1)",
            "delete qlocal(q1)",
            "  DELETE CHANNEL(CH1)",
            "CLEAR QLOCAL(Q1)",
            "ALTER QLOCAL(Q1) MAXDEPTH(10)",
            "STOP CHANNEL(CH1)",
            "RESET QMGR",
        ).forEach { assertTrue(MqscParser.isDangerous(it), "应判定为危险：$it") }
    }

    @Test
    fun `dangerous verbs - 缩写与全称同样危险（防绕过）`() {
        // 这些是 README 推荐的写法，必须弹确认框。
        listOf(
            "del q(Q1)",
            "del ql(Q1)",
            "del chl(CH1)",
            "clr q(Q1)",
            "alt q(Q1) MAXDEPTH(10)",
            "sto chl(CH1)",
            "res qmgr",
            // 大小写混写同样不能漏
            "Del Q(Q1)",
            "CLR q(Q1)",
        ).forEach { assertTrue(MqscParser.isDangerous(it), "缩写必须与全称同判：$it") }
    }

    @Test
    fun `read-only and unknown commands - 不误报`() {
        listOf(
            "DISPLAY QLOCAL(*)",
            "dis q(*)",
            "dis chl(*)",
            "DEFINE QLOCAL(Q1)",
            "def ql(Q1) MAXDEPTH(5000)",
            "PING QMGR",
            "ping qmgr",
            "START CHANNEL(CH1)",
            "sta chl(CH1)",
            "frob q(*)",
            "",
            "* DELETE QLOCAL(Q1)", // 注释行：调用方本就跳过，这里也不该命中
        ).forEach { assertFalse(MqscParser.isDangerous(it), "不应判定为危险：$it") }
    }

    @Test
    fun `verb match is whole-word - 前缀相同的普通词不算`() {
        // 旧实现用 startsWith，会把「以 STOP 开头」的未知词误判成危险；
        // 现在按首个整词比较。误报比漏报轻，但仍然应当准确。
        assertFalse(MqscParser.isDangerous("STOPPED"))
        assertFalse(MqscParser.isDangerous("DELETION"))
    }
}
