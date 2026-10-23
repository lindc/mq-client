package com.mqclient.rabbitmq.ui

import com.mqclient.rabbitmq.RabbitMqScanType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「部分数据未刷新」提示条的**纯逻辑**契约（不碰 Compose 运行时）。
 *
 * 提示条本身是一行 Compose，但它是否显示、显示什么，取决于两个纯函数 ——
 * 那两处才是会悄悄坏掉的地方（比如新增了第五类查询却忘了给文案，界面上就会出现
 * 裸 key 或英文单词）。
 */
class RabbitMqPartialScanBannerTest {

    @Test
    fun `no failures means no banner`() {
        assertEquals(
            emptyList(),
            scanFailureKinds(emptyMap(), "conn-1"),
        )
    }

    @Test
    fun `only this connection's failures are shown`() {
        val failures = mapOf(
            "conn-1" to setOf(RabbitMqScanType.NODES),
            "conn-2" to setOf(RabbitMqScanType.QUEUES),
        )

        assertEquals(listOf(RabbitMqScanType.NODES), scanFailureKinds(failures, "conn-1"))
        assertEquals(emptyList(), scanFailureKinds(failures, "conn-3"), "别的连接/未登记连接不该受影响")
    }

    @Test
    fun `kinds are ordered by declaration, not by map iteration`() {
        // Set 的迭代顺序在 Kotlin 里不保证（HashSet 尤其），文案顺序不能跟着抖。
        val failures = mapOf(
            "conn" to setOf(
                RabbitMqScanType.NODES,
                RabbitMqScanType.EXCHANGES,
                RabbitMqScanType.BINDINGS,
            ),
        )

        assertEquals(
            listOf(
                RabbitMqScanType.EXCHANGES,
                RabbitMqScanType.BINDINGS,
                RabbitMqScanType.NODES,
            ),
            scanFailureKinds(failures, "conn"),
        )
    }

    @Test
    fun `every scan kind has a distinct i18n key`() {
        val keys = RabbitMqScanType.entries.map { it.i18nKey() }

        assertEquals(RabbitMqScanType.entries.size, keys.toSet().size, "不同类型的文案键不能重复：$keys")
        keys.forEach { key ->
            assertTrue(
                key.startsWith("rabbitmq_ui_scan_kind_"),
                "键要落在模块命名空间内，实际：$key",
            )
        }
    }

    @Test
    fun `i18n keys map one-to-one to the enum`() {
        // 这条用例的作用是「加枚举值时被提醒」：新增一类查询后这里会红，
        // 逼着补一条 when 分支 + 两条翻译，而不是界面上显示裸 key。
        assertEquals(
            mapOf(
                RabbitMqScanType.EXCHANGES to "rabbitmq_ui_scan_kind_exchanges",
                RabbitMqScanType.QUEUES to "rabbitmq_ui_scan_kind_queues",
                RabbitMqScanType.BINDINGS to "rabbitmq_ui_scan_kind_bindings",
                RabbitMqScanType.NODES to "rabbitmq_ui_scan_kind_nodes",
            ),
            RabbitMqScanType.entries.associateWith { it.i18nKey() },
        )
    }
}
