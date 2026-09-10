package com.mqclient.core

import com.mqclient.core.scheduler.ScheduledJob
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * CoreModule 与调度器的接线：`initialize` 建出调度器，`shutdown` 先把定时任务停掉，
 * 再收事件订阅与 scope。
 *
 * ⚠️ CoreModule 是进程级单例，所以这个用例自己负责收尾（finally 里兜一次 shutdown），
 * 免得把状态留给同一 JVM 里的其它测试。
 */
class CoreModuleSchedulerTest {

    @Test
    fun `initialize wires a scheduler and shutdown stops its jobs`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            CoreModule.initialize(scope)
            assertTrue(CoreModule.isInitialized)

            val scheduler = CoreModule.jobScheduler
            val runs = AtomicInteger()
            scheduler.schedule(
                ScheduledJob(id = "core.test.tick", intervalMs = 30L, runOnStart = true) { runs.incrementAndGet() },
            )
            await { runs.get() >= 2 }
            assertEquals(listOf("core.test.tick"), scheduler.getActiveJobs())

            CoreModule.shutdown()

            assertFalse(CoreModule.isInitialized)
            assertEquals(emptyList(), scheduler.getActiveJobs(), "shutdown 之后不该还有活动任务（握着的旧引用也一样）")

            delay(100L)
            val settled = runs.get()
            delay(100L)
            assertEquals(settled, runs.get(), "退出之后任务不再执行")
        } finally {
            if (CoreModule.isInitialized) CoreModule.shutdown()
            scope.cancel()
        }
    }

    /** 轮询等待条件成立；超时即测试失败。 */
    private suspend fun await(timeoutMillis: Long = 5_000L, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(5)
        }
    }
}
