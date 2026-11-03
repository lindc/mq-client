package com.mqclient.rocketmq

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「哪几类没取到」的模块级状态（`RocketMqModule.scanFailuresFlow`）契约。
 *
 * 存在理由与 RabbitMQ / Kafka 同构：事件是瞬时的，用户切 Tab 再切回来时订阅者已重建，
 * `OBJECTS_SCANNED` 早就过去了 —— 只靠事件的话提示条会消失而数据仍是旧的。
 * 三条生命周期必须钉住：扫全要清、断开要清、别的连接不受影响。
 */
class RocketMqScanFailuresStateTest {

    private val connectionId = "conn-under-test"

    @AfterTest
    fun cleanup() {
        RocketMqModule.clearScanFailures(connectionId)
        RocketMqModule.clearScanFailures("other-conn")
    }

    private fun failuresFor(id: String): Set<RocketMqScanKind> =
        RocketMqModule.scanFailuresFlow.value[id].orEmpty()

    @Test
    fun `partial scan is recorded`() {
        RocketMqModule.reportScanFailures(connectionId, setOf(RocketMqScanKind.GROUPS))

        assertEquals(setOf(RocketMqScanKind.GROUPS), failuresFor(connectionId))
    }

    @Test
    fun `a fully successful scan clears the record`() {
        RocketMqModule.reportScanFailures(connectionId, setOf(RocketMqScanKind.GROUPS))

        RocketMqModule.reportScanFailures(connectionId, emptySet())

        assertTrue(connectionId !in RocketMqModule.scanFailuresFlow.value, "扫全之后不该还留着该连接的条目")
    }

    @Test
    fun `disconnect clears the record`() {
        RocketMqModule.reportScanFailures(connectionId, setOf(RocketMqScanKind.TOPICS, RocketMqScanKind.GROUPS))

        RocketMqModule.clearScanFailures(connectionId)

        assertTrue(connectionId !in RocketMqModule.scanFailuresFlow.value, "断开后旧结论不能诈尸到重连")
    }

    @Test
    fun `one connection's failures do not leak into another`() {
        RocketMqModule.reportScanFailures(connectionId, setOf(RocketMqScanKind.GROUPS))
        RocketMqModule.reportScanFailures("other-conn", setOf(RocketMqScanKind.TOPICS))

        assertEquals(setOf(RocketMqScanKind.GROUPS), failuresFor(connectionId))
        assertEquals(setOf(RocketMqScanKind.TOPICS), failuresFor("other-conn"))

        RocketMqModule.clearScanFailures("other-conn")

        assertEquals(setOf(RocketMqScanKind.GROUPS), failuresFor(connectionId), "清别的连接不该动到我")
    }

    @Test
    fun `later scan replaces the previous record instead of accumulating`() {
        RocketMqModule.reportScanFailures(connectionId, setOf(RocketMqScanKind.GROUPS))
        // 第二轮换了一类失败：上一轮结论必须被替换（一轮扫描只有一份真相，不是并集）。
        RocketMqModule.reportScanFailures(connectionId, setOf(RocketMqScanKind.TOPICS))

        assertEquals(setOf(RocketMqScanKind.TOPICS), failuresFor(connectionId))
    }
}
