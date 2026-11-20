package com.mqclient.starrocks.system

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 有界尾读的守卫：FE 的 `/log` 总是返回整份文件，客户端必须只保留尾部。
 *
 * 这些用例瞄准的是**内存形状**而非行为 —— 用 3MB 假数据跑一次，断言只拿到最后
 * 1MB 并且内容是最新的；如果实现退回 `readBytes()` 再截取，行为仍然正确，
 * 但这条用例的存在提醒后来者：那正是要避免的 OOM 形态。
 */
class StarRocksFeLogTailReadTest {

    private val http get() = DefaultFeLogHttp

    @Test
    fun `short body is returned whole`() {
        val body = "line1\nline2\nline3"
        val text = http.readTailText(ByteArrayInputStream(body.toByteArray()), maxBytes = 1024)
        assertEquals(body, text)
    }

    @Test
    fun `long body keeps only the newest bytes`() {
        // 3MB，每行带序号：尾部必须是最后几行，而不是开头。
        val builder = StringBuilder()
        var i = 0
        while (builder.length < 3_000_000) {
            builder.append("line-").append(i).append('-').append("x".repeat(64)).append('\n')
            i++
        }
        val body = builder.toString()
        val text = http.readTailText(
            ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
            maxBytes = 1024 * 1024,
        )

        assertTrue(text.length <= 1024 * 1024 + 8, "尾部窗口不应超过上限，实际 ${text.length}")
        assertTrue(text.contains("line-${i - 2}-"), "必须包含接近末尾的行")
        assertTrue(!text.contains("line-0-"), "开头的内容必须已被丢弃")
        assertTrue(body.endsWith(text), "保留的必须是原文的真实后缀")
    }

    @Test
    fun `exactly max bytes is kept`() {
        val body = "a".repeat(1000)
        val text = http.readTailText(ByteArrayInputStream(body.toByteArray()), maxBytes = 1000)
        assertEquals(1000, text.length)
    }

    @Test
    fun `empty stream yields empty text`() {
        assertEquals("", http.readTailText(ByteArrayInputStream(ByteArray(0)), maxBytes = 64))
    }
}
