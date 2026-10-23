package com.mqclient.ibmmq.ui

import com.mqclient.ibmmq.pcf.MqObjectType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「部分数据未刷新」提示条的**纯逻辑**契约（不碰 Compose 运行时）。
 *
 * 与 RabbitMQ / Kafka / RocketMQ 的同名测试同构：提示条本身是一行 Compose，但
 * 「是否显示 / 显示什么」取决于两个纯函数 —— 那两处才是会悄悄坏掉的地方。
 *
 * IBM MQ 多一点特殊：它复用导航项文案（`ibm_mq_nav_*`），提示条里说的类型名与
 * 左侧导航一致，用户不用做第二次对应。队列管理器没有导航项，单独一个键。
 */
class IbmMqPartialScanBannerTest {

    @Test
    fun `no failures means no banner`() {
        assertEquals(emptyList(), scanFailureTypes(emptyMap(), "conn-1"))
    }

    @Test
    fun `only this connection's failures are shown`() {
        val failures = mapOf(
            "conn-1" to setOf(MqObjectType.SUBSCRIPTION),
            "conn-2" to setOf(MqObjectType.QUEUE),
        )

        assertEquals(listOf(MqObjectType.SUBSCRIPTION), scanFailureTypes(failures, "conn-1"))
        assertEquals(emptyList(), scanFailureTypes(failures, "conn-3"), "未登记的连接不该受影响")
    }

    @Test
    fun `types are ordered by declaration, not by map iteration`() {
        // Set 的迭代顺序在 Kotlin 里不保证，文案顺序不能跟着抖。
        val failures = mapOf(
            "conn" to setOf(MqObjectType.COMM_INFO, MqObjectType.QUEUE, MqObjectType.CHANNEL),
        )

        assertEquals(
            listOf(MqObjectType.QUEUE, MqObjectType.CHANNEL, MqObjectType.COMM_INFO),
            scanFailureTypes(failures, "conn"),
        )
    }

    @Test
    fun `every object type has a distinct i18n key`() {
        val keys = MqObjectType.entries.map { it.i18nKey() }

        assertEquals(MqObjectType.entries.size, keys.toSet().size, "不同类型的文案键不能重复：$keys")
        keys.forEach { key ->
            assertTrue(
                key.startsWith("ibm_mq_"),
                "键要落在模块命名空间内，实际：$key",
            )
        }
    }

    @Test
    fun `non-qmgr types reuse the nav labels`() {
        // 复用导航文案是有意的：提示条与左侧导航用同一个词，用户不需要做第二次对应。
        assertEquals("ibm_mq_nav_queues", MqObjectType.QUEUE.i18nKey())
        assertEquals("ibm_mq_nav_channels", MqObjectType.CHANNEL.i18nKey())
        assertEquals("ibm_mq_nav_process_definitions", MqObjectType.PROCESS.i18nKey())
        // 队列管理器没有导航项，只能单独一个键。
        assertEquals("ibm_mq_scan_kind_queue_manager", MqObjectType.QUEUE_MANAGER.i18nKey())
    }
}
