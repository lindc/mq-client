package com.mqclient.kafka

import com.mqclient.core.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「假未连接」回归契约（纯逻辑，不碰网络 / 不碰真集群）。
 *
 * 与 `RabbitMqConnectionGuardTest` 同一组断言：这三个模块的扫描服务是平行实现，
 * 缺陷也曾经是同一份 —— 单次扫描失败即把连接标成 `Failed`，而扫描入口又要求
 * `Connected`，于是判死之后心跳再也不跑，健康连接永久显示「未连接」。
 *
 * 这里钉住两件事：
 *  1. 连续失败阈值：单次 / 两次抖动不判死，三次才算；
 *  2. 拒绝文案按状态区分，`Failed` 必须带出上一次的真实原因（不是只有「未连接」）。
 */
class KafkaConnectionGuardTest {

    private fun ctx(
        status: ConnectionStatus,
        lastError: String? = null,
    ): KafkaConnectionContext = KafkaConnectionContext("conn-1", KafkaConfig()).apply {
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
        // 与 RabbitMQ / RocketMQ 同口径：单次抖动被吃掉，真断线也够快暴露。
        assertEquals(3, SCAN_FAILURE_THRESHOLD)
    }

    /* ------------------------- 拒绝文案分层 ------------------------- */

    @Test
    fun `Failed 状态必须带出上一次的真实原因`() {
        val error = connectionRefusal(
            ctx(ConnectionStatus.Failed, lastError = "Timed out waiting for a node assignment"),
        )
        assertEquals("kafka_err_refusal_failed", error.errorKey)
        // 真实原因经 args 透传（展示端 withArg 插值）。
        assertEquals(listOf("Timed out waiting for a node assignment"), error.args)
    }

    @Test
    fun `Failed 但原因是空的也要可读`() {
        val error = connectionRefusal(ctx(ConnectionStatus.Failed))
        assertEquals(listOf("unknown reason"), error.args, "不能出现空参数：${error.args}")
    }

    @Test
    fun `Connecting 提示等待而不是报错`() {
        assertEquals("kafka_err_refusal_connecting", connectionRefusal(ctx(ConnectionStatus.Connecting)).errorKey)
        assertTrue(connectionRefusal(ctx(ConnectionStatus.Connecting)).message!!.contains("Connecting"), "连接中应该提示等待")
    }

    @Test
    fun `Disconnected 指去侧边栏`() {
        assertTrue(connectionRefusal(ctx(ConnectionStatus.Disconnected)).message!!.contains("sidebar"), "未连接应该指去侧栏")
    }

    @Test
    fun `四种状态的文案互不相同`() {
        val texts = ConnectionStatus.entries.map { connectionRefusal(ctx(it)).message!! }
        assertEquals(
            ConnectionStatus.entries.size,
            texts.toSet().size,
            "四种情况不能用同一句话糊过去（旧的写法全叫「未连接」）：$texts",
        )
    }
}
