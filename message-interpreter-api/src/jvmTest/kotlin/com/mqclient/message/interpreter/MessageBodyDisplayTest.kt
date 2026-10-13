package com.mqclient.message.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [isProbablyBinary] / [hexPreview] 的行为验证。
 *
 * 这两个函数是各 MQ 模块消息详情页共用的「字节流怎么展示」判定 —— 判定口径必须
 * 稳定，同一份字节在不同模块里显示出「文本 / 乱码」两种结论是最难查的那类 bug，
 * 所以边界（8KB 扫描窗口、5% 替换符占比）逐个钉死。
 */
class MessageBodyDisplayTest {

    /* ------------------------------ isProbablyBinary ------------------------------ */

    @Test
    fun `empty content is not binary`() {
        assertFalse(isProbablyBinary(ByteArray(0), CCSID_UTF8))
    }

    @Test
    fun `plain ascii is not binary`() {
        assertFalse(isProbablyBinary("hello world".toByteArray(Charsets.UTF_8), CCSID_UTF8))
    }

    @Test
    fun `null byte is binary`() {
        val content = "some text".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        assertTrue(isProbablyBinary(content, CCSID_UTF8))
    }

    @Test
    fun `null byte at the last scanned index is binary`() {
        // 扫描窗口是前 8KB（索引 0..8191），最后一个索引必须落在窗口内。
        val content = ByteArray(SCAN_WINDOW) { 'a'.code.toByte() }
        content[SCAN_WINDOW - 1] = 0
        assertTrue(isProbablyBinary(content, CCSID_UTF8))
    }

    @Test
    fun `null byte just past the scan window is not detected`() {
        // 同一份内容往后挪一个字节：NUL 落在索引 8192，超出扫描窗口。
        // NUL 本身是合法 UTF-8（U+0000），解码不产生替换符，因此判为非二进制 ——
        // 这是「只扫前 8KB」的既定口径，不是 bug，用例把它钉住避免无意改动。
        val content = ByteArray(SCAN_WINDOW + 1) { 'a'.code.toByte() }
        content[SCAN_WINDOW] = 0
        assertFalse(isProbablyBinary(content, CCSID_UTF8))
    }

    @Test
    fun `utf8 chinese text is not binary`() {
        assertFalse(isProbablyBinary("你好，世界".toByteArray(Charsets.UTF_8), CCSID_UTF8))
    }

    @Test
    fun `gbk text decoded with its own ccsid is not binary`() {
        // 证明 codedCharSetId 真的被用上：这几个字节只有按 GBK 解才成立。
        val gbk = "你好，世界".toByteArray(java.nio.charset.Charset.forName("GBK"))
        assertFalse(isProbablyBinary(gbk, CCSID_GBK))
    }

    @Test
    fun `all invalid bytes are binary`() {
        // 全 0xFF：无论解码器把它们算作几个替换符，占比都是 1.0。
        assertTrue(isProbablyBinary(ByteArray(100) { 0xFF.toByte() }, CCSID_UTF8))
    }

    @Test
    fun `replacement ratio of exactly five percent is not binary`() {
        // 判定是「> 5%」而不是「>= 5%」：100 个字符里正好 5 个替换符 → 不判二进制。
        val content = contentWithBadBytes(badBytes = 5, plainChars = 95)
        assertFalse(isProbablyBinary(content, CCSID_UTF8))
    }

    @Test
    fun `replacement ratio above five percent is binary`() {
        // 同上比例再高一点点：100 个字符里 6 个替换符 → 6% → 二进制。
        val content = contentWithBadBytes(badBytes = 6, plainChars = 94)
        assertTrue(isProbablyBinary(content, CCSID_UTF8))
    }

    /* ------------------------------ hexPreview ------------------------------ */

    @Test
    fun `empty content renders nothing`() {
        assertEquals("", hexPreview(ByteArray(0)))
    }

    @Test
    fun `single byte row is padded to sixteen columns`() {
        // 1 个字节 + 15 组三空格补齐，ASCII 区不足 16 列不补。
        assertEquals(
            "00000000  41 " + "   ".repeat(15) + " |A|\n",
            hexPreview(byteArrayOf(0x41)),
        )
    }

    @Test
    fun `full row has no padding`() {
        assertEquals(
            "00000000  30 31 32 33 34 35 36 37 38 39 41 42 43 44 45 46  |0123456789ABCDEF|\n",
            hexPreview("0123456789ABCDEF".toByteArray(Charsets.US_ASCII)),
        )
    }

    @Test
    fun `non printable bytes render as middle dot`() {
        // 0x00 / 0x1F / 0x7F 都在可打印区间 [0x20, 0x7E] 之外 → '·'。
        val expected = "00000000  00 1F 7F " + "   ".repeat(13) + " |\u00B7\u00B7\u00B7|\n"
        assertEquals(expected, hexPreview(byteArrayOf(0x00, 0x1F, 0x7F)))
    }

    @Test
    fun `printable range boundaries render as themselves`() {
        // 0x20（空格）与 0x7E（~）是可打印区间的两端，必须原样落进 ASCII 列。
        val expected = "00000000  20 7E " + "   ".repeat(14) + " | ~|\n"
        assertEquals(expected, hexPreview(byteArrayOf(0x20, 0x7E)))
    }

    @Test
    fun `second row starts at offset sixteen`() {
        val text = hexPreview(ByteArray(17) { 0x2A })
        // 行数用换行符计：lines() 会把结尾的换行再拆出一个空串，直接 size 会多 1。
        assertEquals(2, text.count { it == '\n' })
        val lines = text.lines()
        assertTrue(lines[0].startsWith("00000000  "), "actual: ${lines[0]}")
        assertTrue(lines[1].startsWith("00000010  "), "actual: ${lines[1]}")
    }

    @Test
    fun `maxBytes truncates and appends ellipsis`() {
        val text = hexPreview(ByteArray(32), maxBytes = 16)
        assertTrue(text.startsWith("00000000  "), "actual: $text")
        assertFalse(text.contains("00000010"), "第二行不该出现: $text")
        assertTrue(text.endsWith("\u2026"), "actual: $text")
    }

    @Test
    fun `default limit is 1024 bytes`() {
        val text = hexPreview(ByteArray(2000))
        // 最后一行是第 63 行（偏移 63 × 16 = 1008 = 0x3F0），再往后被截断。
        assertTrue(text.contains("000003F0  "), "actual tail: ${text.takeLast(120)}")
        assertFalse(text.contains("00000400"), "超出 1024 字节的部分不该出现")
        assertTrue(text.endsWith("\u2026"), "actual tail: ${text.takeLast(20)}")
    }

    @Test
    fun `content exactly at the limit is not marked truncated`() {
        // 1024 字节 = 64 整行，正好等于上限 → 不追加省略号。行数用换行符计。
        val text = hexPreview(ByteArray(1024))
        assertEquals(64, text.count { it == '\n' })
        assertTrue(text.endsWith("\n"), "actual tail: ${text.takeLast(20)}")
        assertFalse(text.endsWith("\u2026"))
    }

    @Test
    fun `maxBytes zero renders only the ellipsis`() {
        assertEquals("\u2026", hexPreview(ByteArray(32), maxBytes = 0))
    }

    /* ------------------------------ helpers ------------------------------ */

    private companion object {
        /** 与 MessageCharsets 的常用 CCSID 对齐：1208 = UTF-8，936 = GBK。 */
        const val CCSID_UTF8 = 1208
        const val CCSID_GBK = 936

        /** isProbablyBinary 的 NUL 扫描窗口（前 8KB）。 */
        const val SCAN_WINDOW = 8192

        /**
         * 造一份「按 UTF-8 解码后正好 [plainChars] 个 'a' + [badBytes] 个替换符」的内容：
         * 非法字节用 0xFF（UTF-8 下的 malformed unit），按等距铺开且**两两之间必有
         * 'a'**，保证解码器逐个替换、不会把相邻非法字节并成一段（那会少算替换符，
         * 让比例边界用例失效）。不用 0x00 是因为它会先命中 NUL 扫描。
         */
        fun contentWithBadBytes(badBytes: Int, plainChars: Int): ByteArray {
            val total = badBytes + plainChars
            val content = ByteArray(total) { 'a'.code.toByte() }
            repeat(badBytes) { i -> content[i * total / badBytes] = 0xFF.toByte() }
            return content
        }
    }
}
