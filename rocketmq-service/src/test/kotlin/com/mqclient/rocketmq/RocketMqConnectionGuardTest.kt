package com.mqclient.rocketmq

import com.mqclient.core.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「假未连接」回归契约（纯逻辑，不碰网络 / 不碰真集群）。
 *
 * 与 `RabbitMqConnectionGuardTest` / `KafkaConnectionGuardTest` 同一组断言：三个模块
 * 的扫描服务是平行实现，缺陷也曾经是同一份 —— 单次扫描失败即把连接标成 `Failed`，
 * 而扫描入口又要求 `Connected`，于是判死之后心跳再也不跑，健康连接永久显示「未连接」。
 */
class RocketMqConnectionGuardTest {

    private fun ctx(
        status: ConnectionStatus,
        lastError: String? = null,
    ): RocketMqConnectionContext = RocketMqConnectionContext("conn-1", RocketMqConfig()).apply {
        this.status = status
        this.lastError = lastError
    }

    /* ------------------------- 连续失败阈值 ------------------------- */

    @Test
    fun `连续失败计数从 1 开始并在重置后归零`() {
        val c = ctx(ConnectionStatus.Connected)
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
        val c = ctx(ConnectionStatus.Connected)
        c.recordScanFailure()
        c.recordScanFailure()
        c.cleanup()
        assertEquals(0, c.scanFailureCount(), "旧连接的失败次数不能诈尸到重连后")
    }

    @Test
    fun `阈值常量是 3`() {
        assertEquals(3, SCAN_FAILURE_THRESHOLD)
    }

    /* ------------------------- 拒绝文案分层 ------------------------- */

    /** 拒结返回 [com.mqclient.core.ServiceException]：errorKey 供展示端翻译，message 是英文兜底。 */
    private fun refusalText(ctx: RocketMqConnectionContext): String = connectionRefusal(ctx).message!!

    @Test
    fun `Failed 状态必须带出上一次的真实原因`() {
        val error = connectionRefusal(
            ctx(ConnectionStatus.Failed, lastError = "connect to nameserver failed"),
        )
        assertEquals("rocketmq_err_refusal_failed", error.errorKey)
        // 真实原因经 args 透传（展示端 withArg 插值），不再断言 message 里的措辞。
        assertEquals(listOf("connect to nameserver failed"), error.args)
        assertTrue(error.message!!.contains("last request failed"), "英文兜底要说明是「上次请求」：${error.message}")
    }

    @Test
    fun `Failed 但原因是空的也要可读`() {
        val error = connectionRefusal(ctx(ConnectionStatus.Failed))
        assertEquals(listOf("unknown reason"), error.args, "不能出现空参数：${error.args}")
    }

    @Test
    fun `Connecting 提示等待而不是报错`() {
        val text = refusalText(ctx(ConnectionStatus.Connecting))
        assertEquals("rocketmq_err_refusal_connecting", connectionRefusal(ctx(ConnectionStatus.Connecting)).errorKey)
        assertTrue(text.contains("Connecting"), "连接中应该提示等待：$text")
    }

    @Test
    fun `Disconnected 指去侧边栏`() {
        val text = refusalText(ctx(ConnectionStatus.Disconnected))
        assertTrue(text.contains("sidebar"), "未连接应该指去侧栏：$text")
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
