package com.mqclient.uicommon.dialog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MessageDetailUiState] 的契约：**状态与渲染结果和窗口生命周期解耦**。
 *
 * 为什么值得钉住：宿主（IBM / Kafka）此前按三态各 new 一个 `ObjectDetailDialog`，
 * 每翻一次状态就重建一次 Tao 原生窗口。现场日志里两个后果都出现过 ——
 * `msg detail completed duration=68ms` 之后界面空了 7.5 秒才开始渲染，以及同一条消息
 * 被取两次（窗口重建把 LaunchedEffect 一起取消，新窗口的 effect 重跑）。
 * 状态提到这个类之后，宿主只建一次窗口；这条测试守的是「提到这里的状态确实自洽」：
 * 构造一次算好不可变部分、切解释器会重置参数、apply 落结果。
 */
class MessageDetailUiStateTest {

    private fun stateFor(body: ByteArray, codedCharSetId: Int = 1208): MessageDetailUiState =
        MessageDetailUiState(
            rawBody = body,
            encoding = 0,
            codedCharSetId = codedCharSetId,
            decodeFailedMessage = "decode failed",
            renderTimeoutReason = "render timeout",
        )

    @Test
    fun `immutable parts are computed once at construction`() {
        val body = "hello".toByteArray()
        val state = stateFor(body)
        assertFalse(state.oversized)
        // 未超限时**不复制**：同一份数组，key 稳定，效果协程不会因为新数组无限重启。
        assertTrue(state.effectiveBody === body, "未超限的消息体不应复制出新数组")
    }

    @Test
    fun `oversized body is truncated to the render budget`() {
        val limit = 1024 * 1024
        val body = ByteArray(limit + 4096) { 'a'.code.toByte() }
        val state = stateFor(body)
        assertTrue(state.oversized)
        assertEquals(limit, state.effectiveBody.size)
        // 原始字节保持完整：下载 / 复制走 rawBody，不受渲染上限影响。
        assertEquals(limit + 4096, state.rawBody.size)
    }

    @Test
    fun `binary body defaults to the hex interpreter`() {
        // 含 0x00 → isProbablyBinary 判为二进制。
        val state = stateFor(byteArrayOf(0, 1, 2, -2, -1))
        assertTrue(state.binary)
        assertEquals("hex", state.selectedId, "二进制内容应默认选中 Hex 解释器")
    }

    @Test
    fun `params follow the selected interpreter and reset on switch`() {
        val state = stateFor("plain text".toByteArray())
        assertEquals(
            state.interpreter?.defaultParams,
            state.params,
            "初始参数应等于默认解释器的默认值",
        )

        state.updateParams(state.params + ("custom" to "value"))
        val other = state.interpreters.firstOrNull { it.id != state.selectedId }
        assertNotNull(other, "解释器不足两个，这条契约无从验证")
        state.select(other.id)
        assertEquals(other.id, state.selectedId)
        assertEquals(
            state.interpreter?.defaultParams,
            state.params,
            "切换解释器必须重置参数，否则旧参数会漏到新解释器",
        )
    }

    @Test
    fun `selecting the current interpreter keeps edited params`() {
        val state = stateFor("plain text".toByteArray())
        state.updateParams(state.params + ("custom" to "value"))
        state.select(state.selectedId)
        assertEquals("value", state.params["custom"], "重复选中当前解释器不该清掉用户改过的参数")
    }

    @Test
    fun `apply stores preview and rendered but keeps previous rendering flag semantics`() {
        val state = stateFor("{\"a\":1}".toByteArray())
        assertNull(state.preview)
        assertNull(state.rendered)

        val preview = ContentPreview(isBinary = false, rawText = "{\"a\":1}", hex = "")
        state.apply(DetailContent(preview = preview, rendered = RenderedText("formatted")))
        assertNotNull(state.preview)
        assertEquals("{\"a\":1}", state.preview?.rawText)
        assertEquals("formatted", state.rendered?.text)
    }

    @Test
    fun `apply without a rendered result leaves rendered null`() {
        val state = stateFor(ByteArray(0))
        state.apply(DetailContent(preview = ContentPreview(isBinary = false, rawText = "", hex = ""), rendered = null))
        assertNull(state.rendered, "无需渲染（解释器缺席 / 二进制）时 rendered 应保持 null")
    }
}
