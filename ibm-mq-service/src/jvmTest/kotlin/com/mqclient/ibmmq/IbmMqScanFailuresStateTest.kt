package com.mqclient.ibmmq

import com.mqclient.ibmmq.pcf.MqObjectType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「哪几类没取到」的模块级状态（`IbmMQModule.scanFailuresFlow`）契约。
 *
 * 存在理由与 RabbitMQ / Kafka / RocketMQ 同构：事件是瞬时的，用户切 Tab 再切回来时
 * 订阅者已重建，`OBJECTS_SCANNED` 早就过去了 —— 只靠事件的话提示条会消失而数据仍是旧的。
 * 三条生命周期必须钉住：扫全要清、断开要清、别的连接不受影响。
 */
class IbmMqScanFailuresStateTest {

    private val connectionId = "conn-under-test"

    @AfterTest
    fun cleanup() {
        IbmMQModule.clearScanFailures(connectionId)
        IbmMQModule.clearScanFailures("other-conn")
    }

    private fun failuresFor(id: String): Set<MqObjectType> =
        IbmMQModule.scanFailuresFlow.value[id].orEmpty()

    @Test
    fun `partial scan is recorded`() {
        IbmMQModule.reportScanFailures(connectionId, setOf(MqObjectType.SUBSCRIPTION))

        assertEquals(setOf(MqObjectType.SUBSCRIPTION), failuresFor(connectionId))
    }

    @Test
    fun `a fully successful scan clears the record`() {
        IbmMQModule.reportScanFailures(connectionId, setOf(MqObjectType.SUBSCRIPTION))

        IbmMQModule.reportScanFailures(connectionId, emptySet())

        assertTrue(connectionId !in IbmMQModule.scanFailuresFlow.value, "扫全之后不该还留着该连接的条目")
    }

    @Test
    fun `disconnect clears the record`() {
        IbmMQModule.reportScanFailures(connectionId, setOf(MqObjectType.QUEUE, MqObjectType.CHANNEL))

        IbmMQModule.clearScanFailures(connectionId)

        assertTrue(connectionId !in IbmMQModule.scanFailuresFlow.value, "断开后旧结论不能诈尸到重连")
    }

    @Test
    fun `one connection's failures do not leak into another`() {
        IbmMQModule.reportScanFailures(connectionId, setOf(MqObjectType.SUBSCRIPTION))
        IbmMQModule.reportScanFailures("other-conn", setOf(MqObjectType.QUEUE))

        assertEquals(setOf(MqObjectType.SUBSCRIPTION), failuresFor(connectionId))
        assertEquals(setOf(MqObjectType.QUEUE), failuresFor("other-conn"))

        IbmMQModule.clearScanFailures("other-conn")

        assertEquals(setOf(MqObjectType.SUBSCRIPTION), failuresFor(connectionId), "清别的连接不该动到我")
    }

    @Test
    fun `later scan replaces the previous record instead of accumulating`() {
        IbmMQModule.reportScanFailures(connectionId, setOf(MqObjectType.SUBSCRIPTION))
        // 第二轮换了一类失败：上一轮结论必须被替换（一轮扫描只有一份真相，不是并集）。
        IbmMQModule.reportScanFailures(connectionId, setOf(MqObjectType.QUEUE))

        assertEquals(setOf(MqObjectType.QUEUE), failuresFor(connectionId))
    }
}
