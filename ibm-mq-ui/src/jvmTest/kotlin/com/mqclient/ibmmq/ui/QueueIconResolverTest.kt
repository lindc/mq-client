package com.mqclient.ibmmq.ui

import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.pcf.MqObjectType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 队列类型解析的离线单测：MQQT_* 常量值已用 javap 对 allclient 10.0.0.5 核实
 * （LOCAL=1 / MODEL=2 / ALIAS=3 / REMOTE=6；MQUS_NORMAL=0 / TRANSMISSION=1）。
 * 图标映射（resolveIcon）是 when 声明式代码，可用性由主源集编译保证。
 */
class QueueIconResolverTest {

    private fun snapshot(type: Int?, usage: Int? = null) = MqObjectSnapshot(
        name = "Q",
        type = MqObjectType.QUEUE,
        attributes = buildMap {
            type?.let { put("MQIA_Q_TYPE", it) }
            usage?.let { put("MQIA_USAGE", it) }
        },
    )

    @Test
    fun `local queue with normal usage resolves to LOCAL`() {
        assertEquals(QueueType.LOCAL, QueueIconResolver.resolveType(snapshot(1, 0)))
    }

    @Test
    fun `local queue with transmission usage resolves to TRANSMISSION`() {
        assertEquals(QueueType.TRANSMISSION, QueueIconResolver.resolveType(snapshot(1, 1)))
    }

    @Test
    fun `remote alias model types resolve correctly`() {
        assertEquals(QueueType.REMOTE, QueueIconResolver.resolveType(snapshot(6)))
        assertEquals(QueueType.ALIAS, QueueIconResolver.resolveType(snapshot(3)))
        assertEquals(QueueType.MODEL, QueueIconResolver.resolveType(snapshot(2)))
    }

    @Test
    fun `unknown or missing type falls back to UNKNOWN`() {
        assertEquals(QueueType.UNKNOWN, QueueIconResolver.resolveType(snapshot(9999)))
        assertEquals(QueueType.UNKNOWN, QueueIconResolver.resolveType(snapshot(null)))
    }

    @Test
    fun `usage without type attribute still resolves to unknown`() {
        assertEquals(QueueType.UNKNOWN, QueueIconResolver.resolveType(snapshot(null, 1)))
    }
}
