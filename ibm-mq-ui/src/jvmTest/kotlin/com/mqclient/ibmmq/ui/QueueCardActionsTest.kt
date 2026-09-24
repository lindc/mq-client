package com.mqclient.ibmmq.ui

import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.pcf.MqObjectType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 操作按钮分支的离线单测：普通本地队列（QLOCAL=1 且非传输）2 个按钮
 * （属性 + 消息），其他队列只有属性按钮。MQQT/MQUS 常量值经 javap 核实。
 */
class QueueCardActionsTest {

    private fun snapshot(type: Int, usage: Int? = null) = MqObjectSnapshot(
        name = "Q",
        type = MqObjectType.QUEUE,
        attributes = buildMap {
            put("MQIA_Q_TYPE", type)
            usage?.let { put("MQIA_USAGE", it) }
        },
    )

    @Test
    fun `normal local queue gets properties and messages buttons`() {
        val actions = QueueCardActions.create(snapshot(1, 0), {}, {})
        assertEquals(listOf("queue.properties", "queue.messages"), actions.map { it.id })
    }

    @Test
    fun `transmission queue gets properties button only`() {
        val actions = QueueCardActions.create(snapshot(1, 1), {}, {})
        assertEquals(listOf("queue.properties"), actions.map { it.id })
    }

    @Test
    fun `remote alias model queues get properties button only`() {
        for (type in listOf(6, 3, 2)) {   // MQQT_REMOTE / MQQT_ALIAS / MQQT_MODEL
            assertEquals(listOf("queue.properties"), QueueCardActions.create(snapshot(type), {}, {}).map { it.id })
        }
    }

    @Test
    fun `unknown type gets properties button only`() {
        assertEquals(listOf("queue.properties"), QueueCardActions.create(snapshot(9999), {}, {}).map { it.id })
    }
}
