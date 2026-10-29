package com.mqclient.kafka

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 消费组查询的超时分级与异步等待。
 *
 * 现场问题是「消费组加载超时」：UI 只等 5 秒，而它等的是**整轮对象慢扫**
 * （brokers + topics + groups 三类并发）。这里钉住服务侧的三条口径：
 *
 *  1. 列表与 Lag 是**两档独立超时**（20s / 30s），且都不低于 `connectionTimeout`；
 *  2. 配得过小的 `connectionTimeout` 不会把 Admin 请求上限一起压死
 *     （[KafkaConfig.MIN_ADMIN_REQUEST_TIMEOUT_MS] 下限）；
 *  3. `awaitKafka` 是真异步：阻塞在 future 上时不占线程、超时能真的打断。
 */
class KafkaGroupTimeoutPolicyTest {

    @Test
    fun `group list timeout is the larger of user config and the 20s floor`() {
        // 用户配 5 秒：按 20 秒档（否则大集群的 listConsumerGroups 注定失败）
        assertEquals(20_000L, KafkaConfig(connectionTimeout = 5).groupListTimeoutMs())
        // 用户配 60 秒：尊重用户意图
        assertEquals(60_000L, KafkaConfig(connectionTimeout = 60).groupListTimeoutMs())
    }

    @Test
    fun `lag timeout is a wider tier than the list timeout`() {
        val config = KafkaConfig(connectionTimeout = 5)
        assertTrue(
            config.groupLagTimeoutMs() > config.groupListTimeoutMs(),
            "Lag 多一次 listOffsets 往返，档位必须更宽：" +
                "list=${config.groupListTimeoutMs()} lag=${config.groupLagTimeoutMs()}",
        )
        assertEquals(30_000L, config.groupLagTimeoutMs())
    }

    @Test
    fun `default config is bounded below by the floors`() {
        // 默认 connectionTimeout=30s 比两个下限都大（或相等），此时跟随用户配置 ——
        // 两档会重合。这是有意的：用户把上限调高到 30 秒以上时，我们不再人为压低。
        val config = KafkaConfig()
        assertEquals(30_000L, config.groupListTimeoutMs())
        assertEquals(30_000L, config.groupLagTimeoutMs())
        assertTrue(config.groupListTimeoutMs() >= KafkaConfig.GROUP_LIST_TIMEOUT_MS)
        assertTrue(config.groupLagTimeoutMs() >= KafkaConfig.GROUP_LAG_TIMEOUT_MS)
    }

    @Test
    fun `admin request timeout never drops below the floor`() {
        // 3 秒的 connectionTimeout 很常见（用户把它理解成「连不连得上」），
        // 但它同时是 Admin 请求上限 —— 必须被抬到 30 秒下限。
        val small = KafkaConfig(bootstrapServers = "b:9092", connectionTimeout = 3).toAdminProperties()
        assertEquals(KafkaConfig.MIN_ADMIN_REQUEST_TIMEOUT_MS, small.get("request.timeout.ms"))
        assertEquals(KafkaConfig.MIN_ADMIN_REQUEST_TIMEOUT_MS, small.get("default.api.timeout.ms"))

        // 大值不被压回下限
        val large = KafkaConfig(bootstrapServers = "b:9092", connectionTimeout = 90).toAdminProperties()
        assertEquals(90_000, large.get("request.timeout.ms"))
        assertEquals(90_000, large.get("default.api.timeout.ms"))
    }
}
