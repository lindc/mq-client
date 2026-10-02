package com.mqclient.ibmmq.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * dmpmqmsg 格式生成/解析的离线单测。
 *
 * fixture 取自真实 dmpmqmsg 9.2.4.0 对 QM1/DEV.QUEUE.1 的导出（1 条文本消息 +
 * 1 条二进制消息），语法以此为准。
 */
class DmpmqmsgFormatTest {

    /** 真实 dmpmqmsg 输出（原样复制自样例文件）。 */
    private val sample = """
        * DMPMQMSG Version:9.2.4.0 Created:Fri Oct  2 12:20:12 2026
        * Qmgr  = QM1
        * Queue = DEV.QUEUE.1

        A VER 2
        A RPT 0
        A MST 8
        A EXP -1
        A FDB 0
        A ENC 546
        A CCS 819
        A FMT MQSTR
        A PRI 0
        A PER 0
        A MSI 414D5120514D3120202020202020202034BCB46A02EB0340
        A COI 000000000000000000000000000000000000000000000000
        A BOC 0
        A RTQ
        A RTM QM1
        A USR mqadmin
        A ACC 0431303031000000000000000000000000000000000000000000000000000006
        A AIX 2020202020202020202020202020202020202020202020202020202020202020
        A PAT 6
        A PAN amqsput
        A PTD 20261002
        A PTT 12191876
        A AOX 20202020
        A GRP 000000000000000000000000000000000000000000000000
        A MSQ 1
        A OFF 0
        A MSF 0
        A ORL -1
        X 48656C6C6F2066726F6D20436172626F6E20436F6465202D20
        X 74657874206D657373616765204020514D31

        A VER 2
        A RPT 0
        A MST 8
        A EXP -1
        A FDB 0
        A ENC 546
        A CCS 819
        A FMT NONE
        A PRI 0
        A PER 0
        A MSI 414D5120514D31202020202020202020D3ADB33FC0FFEE00
        A COI 000000000000000000000000000000000000000000000000
        A BOC 0
        A RTQ
        A RTM QM1
        A USR mqadmin
        A ACC 0431303031000000000000000000000000000000000000000000000000000006
        A AIX 2020202020202020202020202020202020202020202020202020202020202020
        A PAT 6
        A PAN amqsput
        A PTD 20261002
        A PTT 12191876
        A AOX 20202020
        A GRP 000000000000000000000000000000000000000000000000
        A MSQ 1
        A OFF 0
        A MSF 0
        A ORL -1
        X 89504E470D0A1A0A0000000D49484452000000010000000108
        X 060000001F15C489DEADBEEFCAFEBABE00FF7F80010203FEFD
        X FC000102030405060708090A0B0C

    """.trimIndent().replace("        ", "")

    @Test
    fun `parses two messages from real sample`() {
        val result = DmpmqmsgFormat.parse(sample)
        assertEquals(2, result.messages.size)

        val first = result.messages[0]
        assertEquals("414D5120514D3120202020202020202034BCB46A02EB0340", first.msgIdHex)
        assertEquals(8, first.msgType)
        assertEquals(0, first.persistence)
        assertEquals(0, first.priority)
        assertEquals(-1, first.expiry)
        assertEquals(819, first.ccsid)
        assertEquals("MQSTR", first.format)
        assertEquals("QM1", first.replyToQMgr)
        assertEquals("", first.replyToQueue)
        assertFalse(first.hasCorrelId)
        assertNull(first.correlIdBytes)
        assertEquals("20261002", first.putDate)
        assertEquals("12191876", first.putTime)
    }

    @Test
    fun `parses text and binary bodies`() {
        val messages = DmpmqmsgFormat.parse(sample).messages
        val expectedText = "Hello from Carbon Code - text message @ QM1"
        assertEquals(expectedText, messages[0].body.decodeToString())

        // 二进制体：PNG 魔数开头，25+25+14 = 64 字节
        val body = messages[1].body
        assertEquals(64, body.size)
        assertEquals(0x89.toByte(), body[0])
        assertEquals(0x50.toByte(), body[1])
        assertEquals(0x0A.toByte(), body[7])
        assertEquals(0x00.toByte(), body[8]) // 数据行里嵌的 00 字节不塌
        assertEquals(0x0C.toByte(), body[body.size - 1])
    }

    @Test
    fun `parses file without banner`() {
        // -dH 产物（无文件头）也能读
        val noBanner = sample.lineSequence()
            .filterNot { it.startsWith("*") }
            .joinToString("\n")
        assertEquals(2, DmpmqmsgFormat.parse(noBanner).messages.size)
    }

    @Test
    fun `non dmpmqmsg file yields no messages`() {
        assertTrue(DmpmqmsgFormat.parse("Hello, this is a plain text file.\nWith two lines.\n").messages.isEmpty())
        // 空文件 / 纯注释
        assertTrue(DmpmqmsgFormat.parse("").messages.isEmpty())
        assertTrue(DmpmqmsgFormat.parse("* just a comment\n").messages.isEmpty())
    }

    @Test
    fun `export emits all 28 attributes and hex data lines`() {
        val detail = MessageDetail(
            messageId = "414D5120514D3120202020202020202034BCB46A02EB0340",
            correlationId = "000000000000000000000000000000000000000000000000",
            content = "Hello from Carbon Code - text message @ QM1".toByteArray(Charsets.ISO_8859_1),
            dataLength = 43,
            headers = emptyList(),
            encoding = 546,
            codedCharSetId = 819,
            msgType = 8,
            putDate = "20261002",
            putTime = "12191876",
            priority = 0,
            persistence = 0,
            report = 0,
            feedback = 0,
            expiry = -1,
            format = "MQSTR",
            replyToQueue = "",
            replyToQMgr = "QM1",
            userIdentifier = "mqadmin",
            accountingTokenHex = "0431303031000000000000000000000000000000000000000000000000000006",
            applIdentityDataHex = "2020202020202020202020202020202020202020202020202020202020202020",
            putApplType = 6,
            putApplicationName = "amqsput",
            applOriginDataHex = "20202020",
            groupIdHex = "000000000000000000000000000000000000000000000000",
            msgSeqNumber = 1,
            offset = 0,
            msgFlags = 0,
            originalLength = -1,
            backoutCount = 0,
        )
        val text = DmpmqmsgFormat.export(detail, "DEV.QUEUE.1", "QM1", "9.2.4.0")

        // 结构：banner + Qmgr/Queue + 空行 + 28 个属性行 + 数据行 + 尾空行
        val lines = text.lines()
        assertTrue(lines[0].startsWith("* DMPMQMSG Version:9.2.4.0 Created:"))
        assertEquals("* Qmgr  = QM1", lines[1])
        assertEquals("* Queue = DEV.QUEUE.1", lines[2])
        assertEquals("", lines[3])
        val attrs = lines.drop(4).takeWhile { it.startsWith("A ") }
        assertEquals(28, attrs.size)
        // 字符域 padding 与样例一致（FMT 8 / USR 12 / PAN 28 / RTQ 48 空格 / RTM 48）
        assertTrue(attrs.contains("A FMT MQSTR   "))
        assertTrue(attrs.contains("A USR mqadmin     "))
        assertTrue(attrs.contains("A PAN amqsput                     "))
        assertTrue(attrs.contains("A RTM QM1                                             "))
        // 数据行：25 字节/行（50 hex），43 字节 → 2 行（25 + 18）
        val dataLines = lines.filter { it.startsWith("X ") }
        assertEquals(2, dataLines.size)
        assertEquals(50, dataLines[0].length - 2)
        assertEquals(36, dataLines[1].length - 2)
        // 回读等价：export → parse 得到同一消息体与关键属性
        val parsed = DmpmqmsgFormat.parse(text).messages.single()
        assertEquals(detail.content.decodeToString(), parsed.body.decodeToString())
        assertEquals(detail.messageId, parsed.msgIdHex)
        assertEquals(8, parsed.msgType)
        assertEquals(819, parsed.ccsid)
        assertEquals("MQSTR", parsed.format)
        assertEquals(-1, parsed.expiry)
    }

    @Test
    fun `export keeps empty body`() {
        val detail = DmpmqmsgFormatTestHelper.emptyBodyDetail()
        val text = DmpmqmsgFormat.export(detail, "Q", "QM", "9.2.4.0")
        assertTrue(text.lines().none { it.startsWith("X ") })
        assertEquals(1, DmpmqmsgFormat.parse(text).messages.size)
        assertEquals(0, DmpmqmsgFormat.parse(text).messages[0].body.size)
    }
}

private object DmpmqmsgFormatTestHelper {
    fun emptyBodyDetail(): MessageDetail = MessageDetail(
        messageId = "41", correlationId = "00", content = ByteArray(0), dataLength = 0,
        headers = emptyList(), encoding = 273, codedCharSetId = 1208, msgType = 8,
        putDate = "20261002", putTime = "00000000", priority = 4, persistence = 1,
        report = 0, feedback = 0, expiry = -1, format = "NONE",
        replyToQueue = "", replyToQMgr = "", userIdentifier = "",
        accountingTokenHex = "00", applIdentityDataHex = "20", putApplType = 6,
        putApplicationName = "", applOriginDataHex = "20202020",
        groupIdHex = "00", msgSeqNumber = 1, offset = 0, msgFlags = 0,
        originalLength = -1, backoutCount = 0,
    )
}
