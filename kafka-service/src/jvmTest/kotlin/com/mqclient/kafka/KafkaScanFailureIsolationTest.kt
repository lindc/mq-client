package com.mqclient.kafka

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
 * 盯的是一条真实存在的退化路径：topics / groups 查询失败时兜底为空列表，若照常
 * 写缓存，等于告诉 UI「这个集群没有 topic」—— 列表被清空、徽章归零，而真相是
 * 「这次没读到」。缓存写入的门控在 app-core 的 putIfScanned（有独立测试），这里
 * 验证 kafka 侧的两个判定：单类失败不算断连、取消不被吞。
 */
class KafkaScanFailureIsolationTest {

    @Test
    fun `one kind failing is not a broken connection`() {
        val service = KafkaScanService(context())

        assertTrue(
            !service.isConnectionBroken(setOf(KafkaScanService.KafkaScanKind.TOPICS)),
            "topics 失败（常见于 ACL 限制）不该把连接判成已断",
        )
    }

    @Test
    fun `all three kinds failing means the connection is broken`() {
        val service = KafkaScanService(context())

        assertTrue(service.isConnectionBroken(KafkaScanService.KafkaScanKind.entries.toSet()))
    }

    @Test
    fun `safely records the failure and returns the fallback`() = runBlocking {
        val failures = java.util.concurrent.ConcurrentHashMap<KafkaScanService.KafkaScanKind, String>()
        val service = KafkaScanService(context())

        val rows = service.safely(failures, KafkaScanService.KafkaScanKind.TOPICS, emptyList<String>()) {
            throw IllegalStateException("ACL 拒绝列出主题")
        }

        assertTrue(rows.isEmpty(), "兜底应当给空列表，不往上抛")
        assertEquals("ACL 拒绝列出主题", failures[KafkaScanService.KafkaScanKind.TOPICS])
    }

    @Test
    fun `safely does not swallow cancellation`() = runBlocking {
        val failures = java.util.concurrent.ConcurrentHashMap<KafkaScanService.KafkaScanKind, String>()
        val service = KafkaScanService(context())

        assertFailsWith<CancellationException> {
            service.safely(failures, KafkaScanService.KafkaScanKind.GROUPS, emptyList<String>()) {
                throw CancellationException("模块关闭")
            }
        }
        assertTrue(failures.isEmpty(), "取消不是「这一类失败」，不该登记")
    }

    @Test
    fun `successful safely records nothing`() = runBlocking {
        val failures = java.util.concurrent.ConcurrentHashMap<KafkaScanService.KafkaScanKind, String>()
        val service = KafkaScanService(context())

        val rows = service.safely(failures, KafkaScanService.KafkaScanKind.BROKERS, emptyList<String>()) {
            listOf("b1")
        }

        assertEquals(listOf("b1"), rows)
        assertTrue(failures.isEmpty())
    }

    private fun context(): ServiceContext {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val bus = EventBus(scope)
        return ServiceContext.of(
            moduleId = "kafka-test",
            eventBus = bus,
            parentScope = scope,
            jobScheduler = JobScheduler(scope, bus),
        )
    }
}
