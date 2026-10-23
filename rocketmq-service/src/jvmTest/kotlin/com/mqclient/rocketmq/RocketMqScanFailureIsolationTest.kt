package com.mqclient.rocketmq

import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/**
 * 对象慢扫的**失败隔离**契约（不碰真集群）。
 *
 * 原来的写法是 `runCatching { scanTopics(admin) }.getOrElse { emptyList() }`：
 *  - `runCatching` 连 CancellationException 一起吞 —— 取消被当成「这一类扫空了」，
 *    剩下的请求还要接着发（与项目其他地方的「取消必须原样上抛」纪律不一致）；
 *  - 兜底的空表照常写缓存 —— 集群列表被清空。
 *
 * 这里验证 scanKindOr 的两条：登记失败、不吞取消。
 */
class RocketMqScanFailureIsolationTest {

    @Test
    fun `scanKindOr records the failure and returns the fallback`() = runBlocking {
        val failures = java.util.concurrent.ConcurrentHashMap<RocketMqScanService.RocketMqScanKind, String>()
        val service = RocketMqScanService(context())

        val rows = service.scanKindOr(failures, RocketMqScanService.RocketMqScanKind.TOPICS, emptyList<String>()) {
            throw IllegalStateException("ACL 拒绝列出主题")
        }

        assertTrue(rows.isEmpty(), "兜底应当给空列表，不往上抛")
        assertEquals("ACL 拒绝列出主题", failures[RocketMqScanService.RocketMqScanKind.TOPICS])
    }

    @Test
    fun `scanKindOr does not swallow cancellation`() = runBlocking {
        val failures = java.util.concurrent.ConcurrentHashMap<RocketMqScanService.RocketMqScanKind, String>()
        val service = RocketMqScanService(context())

        assertFailsWith<CancellationException> {
            service.scanKindOr(failures, RocketMqScanService.RocketMqScanKind.GROUPS, emptyList<String>()) {
                throw CancellationException("用户点了停止")
            }
        }
        assertTrue(failures.isEmpty(), "取消不是「这一类失败」，不该登记")
    }

    @Test
    fun `successful scanKindOr records nothing`() = runBlocking {
        val failures = java.util.concurrent.ConcurrentHashMap<RocketMqScanService.RocketMqScanKind, String>()
        val service = RocketMqScanService(context())

        val rows = service.scanKindOr(failures, RocketMqScanService.RocketMqScanKind.TOPICS, emptyList<String>()) {
            listOf("TopicTest")
        }

        assertEquals(listOf("TopicTest"), rows)
        assertTrue(failures.isEmpty())
    }

    private fun context(): ServiceContext {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bus = EventBus(scope)
        return ServiceContext.of(
            moduleId = "rocketmq-test",
            eventBus = bus,
            parentScope = scope,
            jobScheduler = JobScheduler(scope, bus),
        )
    }
}
