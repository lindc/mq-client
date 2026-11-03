package com.mqclient.kafka

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「哪几类没取到」的模块级状态（`KafkaModule.scanFailuresFlow`）契约。
 *
 * 存在理由与 RabbitMQ 同构：事件是瞬时的，用户切 Tab 再切回来时订阅者已重建，
 * `OBJECTS_SCANNED` 早就过去了 —— 只靠事件的话提示条会消失而数据仍是旧的。
 * 三条生命周期必须钉住：扫全要清、断开要清、别的连接不受影响。
 */
class KafkaScanFailuresStateTest {

    private val connectionId = "conn-under-test"

    @AfterTest
    fun cleanup() {
        KafkaModule.clearScanFailures(connectionId)
        KafkaModule.clearScanFailures("other-conn")
    }

    private fun failuresFor(id: String): Set<KafkaScanKind> =
        KafkaModule.scanFailuresFlow.value[id].orEmpty()

    @Test
    fun `partial scan is recorded`() {
        KafkaModule.reportScanFailures(connectionId, setOf(KafkaScanKind.GROUPS))

        assertEquals(setOf(KafkaScanKind.GROUPS), failuresFor(connectionId))
    }

    @Test
    fun `a fully successful scan clears the record`() {
        KafkaModule.reportScanFailures(connectionId, setOf(KafkaScanKind.GROUPS))

        KafkaModule.reportScanFailures(connectionId, emptySet())

        assertTrue(connectionId !in KafkaModule.scanFailuresFlow.value, "扫全之后不该还留着该连接的条目")
    }

    @Test
    fun `disconnect clears the record`() {
        KafkaModule.reportScanFailures(connectionId, setOf(KafkaScanKind.TOPICS, KafkaScanKind.GROUPS))

        KafkaModule.clearScanFailures(connectionId)

        assertTrue(connectionId !in KafkaModule.scanFailuresFlow.value, "断开后旧结论不能诈尸到重连")
    }

    @Test
    fun `one connection's failures do not leak into another`() {
        KafkaModule.reportScanFailures(connectionId, setOf(KafkaScanKind.GROUPS))
        KafkaModule.reportScanFailures("other-conn", setOf(KafkaScanKind.TOPICS))

        assertEquals(setOf(KafkaScanKind.GROUPS), failuresFor(connectionId))
        assertEquals(setOf(KafkaScanKind.TOPICS), failuresFor("other-conn"))

        KafkaModule.clearScanFailures("other-conn")

        assertEquals(setOf(KafkaScanKind.GROUPS), failuresFor(connectionId), "清别的连接不该动到我")
    }

    @Test
    fun `later scan replaces the previous record instead of accumulating`() {
        KafkaModule.reportScanFailures(connectionId, setOf(KafkaScanKind.GROUPS))
        // 第二轮换了一类失败：上一轮结论必须被替换（一轮扫描只有一份真相，不是并集）。
        KafkaModule.reportScanFailures(connectionId, setOf(KafkaScanKind.TOPICS))

        assertEquals(setOf(KafkaScanKind.TOPICS), failuresFor(connectionId))
    }
}
