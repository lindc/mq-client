package com.mqclient.ibmmq.ui.command

import com.mqclient.mqui.command.model.CommandStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 命令控制台适配器的契约测试（迁移到 ibm-mq-ui 后补的第一批）。
 *
 * 这两个类此前在 `ibm-mq-service` 里没有任何测试 —— 它们是把协议实现接到 UI 契约上的
 * 薄层，薄层最容易在重构时被改坏而没人发现（行号错位 = 状态列画到别的行上去；
 * 高亮改了文本 = 用户输入被篡改）。
 */
class MqscCommandConsoleAdapterTest {

    private val parser = MqscCommandParser()

    /* ------------------------------ 解析 ------------------------------ */

    @Test
    fun `parse keeps one entry per input line so the status column stays aligned`() {
        val lines = parser.parse("DISPLAY QLOCAL(*)\n\n* a comment\nDELETE QLOCAL(Q1)")

        assertEquals(4, lines.size, "注释 / 空行也要占一行（状态列按行号对齐）")
        assertEquals(listOf("DISPLAY QLOCAL(*)", "", "* a comment", "DELETE QLOCAL(Q1)"), lines.map { it.text })
    }

    @Test
    fun `blank and comment lines are marked skipped`() {
        val lines = parser.parse("DISPLAY QLOCAL(*)\n\n* note\n   ")

        assertEquals(CommandStatus.NOT_EXECUTED, lines[0].status)
        assertEquals(CommandStatus.SKIPPED, lines[1].status, "空行")
        assertEquals(CommandStatus.SKIPPED, lines[2].status, "注释")
        assertEquals(CommandStatus.SKIPPED, lines[3].status, "纯空白行")
    }

    @Test
    fun `dangerous detection delegates to the service parser`() {
        // 与 service 侧 MqscParser.isDangerous 同判（含缩写形态）。
        assertTrue(parser.isDangerous("DELETE QLOCAL(Q1)"))
        assertTrue(parser.isDangerous("del q(Q1)"), "缩写必须与全称同判")
        assertFalse(parser.isDangerous("DISPLAY QLOCAL(*)"))
        assertFalse(parser.isDangerous("PING QMGR"))
    }
}
