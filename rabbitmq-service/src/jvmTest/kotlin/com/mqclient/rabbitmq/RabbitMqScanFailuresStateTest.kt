package com.mqclient.rabbitmq

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「哪几类没取到」的模块级状态（`RabbitMqModule.scanFailuresFlow`）契约。
 *
 * 这层状态存在的理由不是好看：事件是瞬时的，用户切 Tab 再切回来时订阅者已经重建，
 * `OBJECTS_SCANNED` 早就过去了 —— 只靠事件的话，提示条会消失而数据仍是旧的。
 * 所以它的三条生命周期必须被钉住：**扫全要清、断开要清、别的连接不受影响**。
 */
class RabbitMqScanFailuresStateTest {

    private val connectionId = "conn-under-test"

    @AfterTest
    fun cleanup() {
        RabbitMqModule.clearScanFailures(connectionId)
        RabbitMqModule.clearScanFailures("other-conn")
    }

    private fun failuresFor(id: String): Set<RabbitMqScanType> =
        RabbitMqModule.scanFailuresFlow.value[id].orEmpty()

    @Test
    fun `partial scan is recorded`() {
        RabbitMqModule.reportScanFailures(connectionId, setOf(RabbitMqScanType.NODES))

        assertEquals(setOf(RabbitMqScanType.NODES), failuresFor(connectionId))
    }

    @Test
    fun `a fully successful scan clears the record`() {
        RabbitMqModule.reportScanFailures(connectionId, setOf(RabbitMqScanType.NODES))

        // 下一轮扫全：空集合 = 没有未取到的类型，条目应当整条摘掉而不是留个空集
        // （留空集会让 UI 的 isEmpty 判断依赖两套语义）。
        RabbitMqModule.reportScanFailures(connectionId, emptySet())

        assertTrue(connectionId !in RabbitMqModule.scanFailuresFlow.value, "扫全之后不该还留着该连接的条目")
    }

    @Test
    fun `disconnect clears the record`() {
        RabbitMqModule.reportScanFailures(connectionId, setOf(RabbitMqScanType.BINDINGS, RabbitMqScanType.NODES))

        RabbitMqModule.clearScanFailures(connectionId)

        assertTrue(connectionId !in RabbitMqModule.scanFailuresFlow.value, "断开后旧结论不能诈尸到重连")
    }

    @Test
    fun `one connection's failures do not leak into another`() {
        RabbitMqModule.reportScanFailures(connectionId, setOf(RabbitMqScanType.NODES))
        RabbitMqModule.reportScanFailures("other-conn", setOf(RabbitMqScanType.QUEUES))

        assertEquals(setOf(RabbitMqScanType.NODES), failuresFor(connectionId))
        assertEquals(setOf(RabbitMqScanType.QUEUES), failuresFor("other-conn"))

        RabbitMqModule.clearScanFailures("other-conn")

        assertEquals(setOf(RabbitMqScanType.NODES), failuresFor(connectionId), "清别的连接不该动到我")
    }

    @Test
    fun `later scan replaces the previous record instead of accumulating`() {
        RabbitMqModule.reportScanFailures(connectionId, setOf(RabbitMqScanType.NODES))
        // 第二轮换了一类失败：上一轮的结论必须被替换（不是并集 —— 一轮扫描只有一份真相）。
        RabbitMqModule.reportScanFailures(connectionId, setOf(RabbitMqScanType.EXCHANGES))

        assertEquals(setOf(RabbitMqScanType.EXCHANGES), failuresFor(connectionId))
    }
}
