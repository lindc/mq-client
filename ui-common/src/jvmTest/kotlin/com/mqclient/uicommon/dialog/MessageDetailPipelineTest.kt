package com.mqclient.uicommon.dialog

import com.mqclient.message.interpreter.MessageInterpreters
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 回归：详情框的「解码 → 渲染」是**一次调用跑完**的。
 *
 * ## 为什么这条断言值得存在
 *
 * 真机日志（`[ui.detail]`）测到过这样一段：
 *
 * ```
 * detail composed:      sinceCompose=16ms
 * detail preview ready: elapsed=3ms
 * detail rendered:      render=1ms  total=51021ms   ← 1ms 的活等了 51 秒
 * ```
 *
 * 渲染本身 1ms，却晚启动 51 秒。原因是旧实现把「算基线」和「用解释器渲染」拆成两个
 * `LaunchedEffect`，第二个以 `preview` 为 key。`preview` 一变，第二个 effect 的启动就
 * 必须等**下一帧**（Recomposer 的 `withFrameNanos`），而 Tao 主循环只在收到原生事件时
 * 推进 —— 用户不碰鼠标就没有帧，1ms 的活就能等上几十秒。
 *
 * 现在两段合进 [buildDetailContent]：一次调用同时给出基线与渲染结果。本测试钉住这个
 * 形状 —— 若有人把它拆回去，`rendered` 会变成 null（管线只做了一半），这条就红。
 *
 * 不碰网络、不碰 broker、不用 nucleus 窗口：纯本地管线。
 */
class MessageDetailPipelineTest {

    private val decodeFailed = "decode failed"
    private val renderTimeout = "render timeout"

    private fun interpreter(id: String) = MessageInterpreters.byId(id)

    @Test
    fun `one call returns both preview and rendered text`() = runBlocking {
        val body = """{"orderId":"A-1","amount":42}""".toByteArray()
        val json = assertNotNull(interpreter("json"), "内置 json 解释器未注册")

        val content = buildDetailContent(
            body = body,
            codedCharSetId = 1208,
            encoding = 0,
            binary = false,
            interpreter = json,
            params = json.defaultParams,
            decodeFailedMessage = decodeFailed,
            renderTimeoutReason = renderTimeout,
        )

        assertNull(content.preview.decodeError, "该输入不应解码失败")
        assertEquals(false, content.preview.isBinary)
        assertEquals(String(body), content.preview.rawText.let { it })

        val rendered = assertNotNull(
            content.rendered,
            "渲染结果为空 —— 管线只做了解码，渲染又被挪到「等下一帧」的另一个 effect 上了",
        )
        assertNull(rendered.parseError, "合法 JSON 不该有解析错误：${rendered.parseError}")
        assertTrue(rendered.text.contains("orderId"), "格式化结果里应能看到字段名：${rendered.text}")
    }

    @Test
    fun `binary body skips rendering unless hex is selected`() = runBlocking {
        val body = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val text = assertNotNull(interpreter("text"), "内置 text 解释器未注册")

        val content = buildDetailContent(
            body = body,
            codedCharSetId = 1208,
            encoding = 0,
            binary = true,
            interpreter = text,
            params = text.defaultParams,
            decodeFailedMessage = decodeFailed,
            renderTimeoutReason = renderTimeout,
        )

        assertTrue(content.preview.isBinary)
        assertTrue(content.preview.hex.isNotBlank(), "二进制必须给出 hex 预览")
        assertNull(content.rendered, "二进制 + 文本解释器不该渲染（无意义，也徒增一次调度）")
    }

    @Test
    fun `hex interpreter does render binary bodies`() = runBlocking {
        val body = byteArrayOf(0x00, 0x7F, 0x41)
        val hex = assertNotNull(interpreter("hex"), "内置 hex 解释器未注册")

        val content = buildDetailContent(
            body = body,
            codedCharSetId = 1208,
            encoding = 0,
            binary = true,
            interpreter = hex,
            params = hex.defaultParams,
            decodeFailedMessage = decodeFailed,
            renderTimeoutReason = renderTimeout,
        )

        val rendered = assertNotNull(content.rendered, "hex 解释器应当渲染二进制内容")
        assertTrue(rendered.text.contains("41"), "hex dump 应包含字节值：${rendered.text}")
    }

    /**
     * 一次调用的耗时必须是毫秒级。
     *
     * 这条不是性能门槛，而是**把「管线本身很贵」这个假设钉死**：真机上 40 秒的来源
     * 已经查清是调度（见类 KDoc），不是计算。若哪天这条变红了，说明有人往管线里塞了
     * 真正昂贵的东西 —— 那才需要重新审视。
     */
    @Test
    fun `pipeline completes in milliseconds for a small body`() = runBlocking {
        val body = """{"k":"v"}""".toByteArray()
        val json = assertNotNull(interpreter("json"))
        val start = System.nanoTime()
        repeat(5) {
            buildDetailContent(
                body = body,
                codedCharSetId = 1208,
                encoding = 0,
                binary = false,
                interpreter = json,
                params = json.defaultParams,
                decodeFailedMessage = decodeFailed,
                renderTimeoutReason = renderTimeout,
            )
        }
        val perCallMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) / 5.0
        println("=== DETAIL PIPELINE ===")
        println("  per call = $perCallMs ms")
        println("=== END ===")
        assertTrue(perCallMs < 500, "小消息的整条管线应远快于 500ms，实测 $perCallMs ms")
    }
}
