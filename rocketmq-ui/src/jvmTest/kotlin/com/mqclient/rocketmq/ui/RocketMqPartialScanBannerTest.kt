package com.mqclient.rocketmq.ui

import com.mqclient.rocketmq.RocketMqScanKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「部分数据未刷新」提示条的**纯逻辑**契约（不碰 Compose 运行时）。
 *
 * 与 RabbitMQ / Kafka 的同名测试同构：「是否显示 / 显示什么」取决于两个纯函数 ——
 * 那两处才是会悄悄坏掉的地方（比如新增一类查询却忘了给文案，界面上就会出现裸 key）。
 */
class RocketMqPartialScanBannerTest {

    @Test
    fun `no failures means no banner`() {
        assertEquals(emptyList(), scanFailureKinds(emptyMap(), "conn-1"))
    }

    @Test
    fun `only this connection's failures are shown`() {
        val failures = mapOf(
            "conn-1" to setOf(RocketMqScanKind.GROUPS),
            "conn-2" to setOf(RocketMqScanKind.TOPICS),
        )

        assertEquals(listOf(RocketMqScanKind.GROUPS), scanFailureKinds(failures, "conn-1"))
        assertEquals(emptyList(), scanFailureKinds(failures, "conn-3"), "未登记的连接不该受影响")
    }

    @Test
    fun `kinds are ordered by declaration, not by map iteration`() {
        val failures = mapOf(
            "conn" to setOf(RocketMqScanKind.GROUPS, RocketMqScanKind.TOPICS),
        )

        assertEquals(
            listOf(RocketMqScanKind.TOPICS, RocketMqScanKind.GROUPS),
            scanFailureKinds(failures, "conn"),
        )
    }

    @Test
    fun `every scan kind has a distinct i18n key`() {
        val keys = RocketMqScanKind.entries.map { it.i18nKey() }

        assertEquals(RocketMqScanKind.entries.size, keys.toSet().size, "不同类型的文案键不能重复：$keys")
        keys.forEach { key ->
            assertTrue(key.startsWith("rocketmq_ui_scan_kind_"), "键要落在模块命名空间内，实际：$key")
        }
    }

    @Test
    fun `i18n keys map one-to-one to the enum`() {
        // 新增一类查询后这里会红，逼着补一条 when 分支 + 两条翻译。
        assertEquals(
            mapOf(
                RocketMqScanKind.TOPICS to "rocketmq_ui_scan_kind_topics",
                RocketMqScanKind.GROUPS to "rocketmq_ui_scan_kind_groups",
            ),
            RocketMqScanKind.entries.associateWith { it.i18nKey() },
        )
    }
}
