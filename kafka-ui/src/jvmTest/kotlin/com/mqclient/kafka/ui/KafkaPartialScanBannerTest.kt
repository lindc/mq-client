package com.mqclient.kafka.ui

import com.mqclient.kafka.KafkaScanKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「部分数据未刷新」提示条的**纯逻辑**契约（不碰 Compose 运行时）。
 *
 * 与 RabbitMQ 的同名测试同构：提示条本身是一行 Compose，但「是否显示 / 显示什么」
 * 取决于两个纯函数 —— 那两处才是会悄悄坏掉的地方（比如新增一类查询却忘了给文案，
 * 界面上就会出现裸 key）。
 */
class KafkaPartialScanBannerTest {

    @Test
    fun `no failures means no banner`() {
        assertEquals(emptyList(), scanFailureKinds(emptyMap(), "conn-1"))
    }

    @Test
    fun `only this connection's failures are shown`() {
        val failures = mapOf(
            "conn-1" to setOf(KafkaScanKind.GROUPS),
            "conn-2" to setOf(KafkaScanKind.TOPICS),
        )

        assertEquals(listOf(KafkaScanKind.GROUPS), scanFailureKinds(failures, "conn-1"))
        assertEquals(emptyList(), scanFailureKinds(failures, "conn-3"), "未登记的连接不该受影响")
    }

    @Test
    fun `kinds are ordered by declaration, not by map iteration`() {
        // Set 的迭代顺序在 Kotlin 里不保证，文案顺序不能跟着抖。
        val failures = mapOf(
            "conn" to setOf(KafkaScanKind.GROUPS, KafkaScanKind.BROKERS),
        )

        assertEquals(
            listOf(KafkaScanKind.BROKERS, KafkaScanKind.GROUPS),
            scanFailureKinds(failures, "conn"),
        )
    }

    @Test
    fun `every scan kind has a distinct i18n key`() {
        val keys = KafkaScanKind.entries.map { it.i18nKey() }

        assertEquals(KafkaScanKind.entries.size, keys.toSet().size, "不同类型的文案键不能重复：$keys")
        keys.forEach { key ->
            assertTrue(key.startsWith("kafka_ui_scan_kind_"), "键要落在模块命名空间内，实际：$key")
        }
    }

    @Test
    fun `i18n keys map one-to-one to the enum`() {
        // 新增一类查询后这里会红，逼着补一条 when 分支 + 两条翻译。
        assertEquals(
            mapOf(
                KafkaScanKind.BROKERS to "kafka_ui_scan_kind_brokers",
                KafkaScanKind.TOPICS to "kafka_ui_scan_kind_topics",
                KafkaScanKind.GROUPS to "kafka_ui_scan_kind_groups",
            ),
            KafkaScanKind.entries.associateWith { it.i18nKey() },
        )
    }
}
