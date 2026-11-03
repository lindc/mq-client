package com.mqclient.rabbitmq

import com.mqclient.core.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「假未连接」回归契约（纯逻辑，不碰网络 / 不碰真集群）。
 *
 * 现场（2026-10-26 mqclient.log）：四类对象端点刚全部读成功，5 秒后一次
 * `HTTP/1.1 header parser received no bytes`（长连接池里取到被代理回收的空闲
 * socket）就把整条连接标成 `Failed`。由于扫描入口当时要求 `status == Connected`，
 * 判死之后心跳再也不跑 —— 一条健康的连接永久显示「未连接」。
 *
 * 这里钉住两件事：
 *  1. 连续失败阈值：单次 / 两次抖动不判死，三次才算；
 *  2. 拒绝文案按状态区分，`Failed` 必须带出上一次的真实原因（不是只有「未连接」）。
 */
class RabbitMqConnectionGuardTest {

    private fun ctx(
        status: ConnectionStatus,
        lastError: String? = null,
        hasAdmin: Boolean = false,
    ): RabbitMqConnectionContext = RabbitMqConnectionContext("conn-1", RabbitMqConfig()).apply {
        this.status = status
        this.lastError = lastError
        if (hasAdmin) this.admin = fakeAdmin()
    }

    /** 只需要「非 null」；任何请求都不应当发出。 */
    private fun fakeAdmin(): RabbitMqAdminClient = RabbitMqAdminClient(
        RabbitMqConfig(host = "h", username = "u", password = "p"),
        io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { error("不应当发请求") }),
    )

    /* ------------------------- 连续失败阈值 ------------------------- */

    @Test
    fun `连续失败计数从 1 开始并在重置后归零`() {
        val c = ctx(ConnectionStatus.Connected, hasAdmin = true)
        assertEquals(1, c.recordScanFailure())
        assertEquals(2, c.recordScanFailure())
        assertEquals(3, c.recordScanFailure())
        assertEquals(3, c.scanFailureCount())
        c.resetScanFailures()
        assertEquals(0, c.scanFailureCount())
        assertEquals(1, c.recordScanFailure(), "重置后重新从 1 数起")
    }

    @Test
    fun `断开清理时计数一并归零`() {
        val c = ctx(ConnectionStatus.Connected, hasAdmin = true)
        c.recordScanFailure()
        c.recordScanFailure()
        c.cleanup()
        assertEquals(0, c.scanFailureCount(), "旧连接的失败次数不能诈尸到重连后")
    }

    @Test
    fun `阈值常量是 3`() {
        // 10s 一次心跳 → 约 30s 才判死：单次代理抖动完全被吃掉，真断线也够快。
        assertEquals(3, SCAN_FAILURE_THRESHOLD)
    }

    /* ------------------------- 拒结文案分层 ------------------------- */

    /** 拒结返回 ServiceException：errorKey 供展示端翻译，message 是英文兜底。 */
    private fun refusalText(ctx: RabbitMqConnectionContext): String = connectionRefusal(ctx).message!!

    @Test
    fun `Failed 状态必须带出上一次的真实原因`() {
        val error = connectionRefusal(
            ctx(ConnectionStatus.Failed, lastError = "HTTP/1.1 header parser received no bytes"),
        )
        assertEquals("rabbitmq_err_refusal_failed", error.errorKey)
        // 真实原因经 args 透传（展示端 withArg 插值）。
        assertEquals(listOf("HTTP/1.1 header parser received no bytes"), error.args)
        val text = refusalText(ctx(ConnectionStatus.Failed, lastError = "HTTP/1.1 header parser received no bytes"))
        assertTrue(text.contains("last request failed"), "要说明是「上次请求」而不是笼统未连接：$text")
    }

    @Test
    fun `Failed 但原因是空的也要可读`() {
        val error = connectionRefusal(ctx(ConnectionStatus.Failed))
        assertEquals(listOf("unknown reason"), error.args, "不能出现空参数：${error.args}")
    }

    @Test
    fun `Connecting 提示等待而不是报错`() {
        assertEquals("rabbitmq_err_refusal_connecting", connectionRefusal(ctx(ConnectionStatus.Connecting)).errorKey)
        assertTrue(refusalText(ctx(ConnectionStatus.Connecting)).contains("Connecting"), "连接中应该提示等待")
    }

    @Test
    fun `Disconnected 指去侧边栏`() {
        assertTrue(refusalText(ctx(ConnectionStatus.Disconnected)).contains("sidebar"), "未连接应该指去侧栏")
    }

    @Test
    fun `四种状态的文案互不相同`() {
        val texts = ConnectionStatus.entries.map { refusalText(ctx(it)) }
        assertEquals(
            ConnectionStatus.entries.size,
            texts.toSet().size,
            "四种情况不能用同一句话糊过去（旧的写法全叫「未连接」）：$texts",
        )
    }
}
