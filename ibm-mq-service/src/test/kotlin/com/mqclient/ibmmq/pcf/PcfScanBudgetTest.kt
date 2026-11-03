package com.mqclient.ibmmq.pcf

import com.ibm.mq.pcf.PCFMessage
import com.ibm.mq.pcf.PCFMessageAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 协作式扫描预算的离线单测：不需要真 QMGR（[PCFMessageAgent] 的无参构造不连接）。
 *
 * 这里验证的是本轮修复的**要害**，不是「超时会抛异常」那种表面性质：
 *  - 预算耗尽后**不再发起新的 MQI 调用**（`sends` 不再增长）；
 *  - 在飞的那一次 `send` 被允许跑完（不试图打断 —— 那本来也做不到，`send` 与
 *    `disconnect` 同步在同一个对象上）；
 *  - 因此异常抛在扫描自己的线程上，`withAgent` 的 `finally` 能正常断 agent，
 *    不会留下跑在后台的「幽灵扫描」。
 */
class PcfScanBudgetTest {

    /**
     * 只数 `send` 次数、不碰网络的 agent。
     *
     * `send` 在 Java 侧是 `public synchronized`，Kotlin override 后 `synchronized`
     * 语义仍在（方法修饰符来自父类），这里不需要额外加锁。
     */
    private class FakeAgent(private val sendDelayMs: Long = 0) : PCFMessageAgent() {
        var sends: Int = 0
            private set

        fun reset() {
            sends = 0
        }

        override fun send(message: PCFMessage): Array<PCFMessage> {
            sends++
            // 模拟「一次 MQI 调用正在飞」：不可打断的阻塞。
            if (sendDelayMs > 0) Thread.sleep(sendDelayMs)
            return emptyArray()
        }
    }

    @Test
    fun `budget expires after its total elapses`() {
        val budget = ScanBudget(totalMs = 40)
        assertFalse(budget.expired(), "刚构造就过期，说明计时起点错了")

        Thread.sleep(80)
        assertTrue(budget.expired(), "40ms 预算过了 80ms 还没过期")
    }

    @Test
    fun `check reports how long the budget was`() {
        val budget = ScanBudget(totalMs = 1)
        Thread.sleep(5)

        val thrown = try {
            budget.check()
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue(thrown is ScanBudgetExceededException, "实际抛的是：$thrown")
        assertEquals(1L, thrown.budgetMs)
        assertTrue(
            thrown.message.orEmpty().contains("budget"),
            "文案要能让用户看懂，实际：${thrown.message}",
        )
    }

    @Test
    fun `no inquire is attempted once the budget is gone`() = runBlocking {
        val agent = FakeAgent()
        val session = ScanSession(agent, ScanBudget(totalMs = 1))
        Thread.sleep(5)

        val thrown = try {
            PcfObjectScanner().scanQueueStatusesWith(session)
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue(thrown is ScanBudgetExceededException, "实际抛的是：$thrown")
        assertEquals(0, agent.sends, "预算已耗尽就不该再发 PCF 请求")
    }

    @Test
    fun `a fresh budget lets the query through`() = runBlocking {
        val agent = FakeAgent()
        val session = ScanSession(agent, ScanBudget(totalMs = 60_000))

        val rows = PcfObjectScanner().scanQueueStatusesWith(session)

        assertTrue(rows.isEmpty())
        assertEquals(1, agent.sends, "一条 INQUIRE 应当恰好发一次")
    }

    @Test
    fun `in-flight send is allowed to finish and then the scan stops`() = runBlocking {
        val agent = FakeAgent(sendDelayMs = 300)
        val scanner = PcfObjectScanner()

        // 预热：把 PCF / JMQI 的类初始化开销挪出计时窗口。否则第一次 check 就可能
        // 已经因为类加载而超时，测试会变成「偶发失败」——正是要避免的那类抖动。
        scanner.scanQueueStatusesWith(ScanSession(agent, ScanBudget(totalMs = 60_000)))
        agent.reset()

        // scanChannelsWith 要发两条 INQUIRE：第一条在飞 300ms，预算 150ms。
        val session = ScanSession(agent, ScanBudget(totalMs = 150))
        val thrown = try {
            scanner.scanChannelsWith(session)
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue(thrown is ScanBudgetExceededException, "实际抛的是：$thrown")
        assertEquals(
            1,
            agent.sends,
            "在飞的那次应当跑完（不打断），第二条应当被预算拦下；实际发了 ${agent.sends} 次",
        )
    }

    @Test
    fun `default budget is bounded`() {
        // 防回归：把默认值改成 Long.MAX_VALUE / 不设上限，协作式超时就静默失效了。
        assertEquals(
            60_000L,
            ScanSession(FakeAgent()).budget.totalMs,
            "默认预算应当是 60s",
        )
    }

    @Test
    fun `partial is derived from failed types`() {
        val complete = scanResult(failedTypes = emptySet())
        val partial = scanResult(failedTypes = setOf(MqObjectType.QUEUE, MqObjectType.CHANNEL))

        assertFalse(complete.partial, "全部扫到就不该标部分完成")
        assertTrue(partial.partial, "有类型没扫全就必须标部分完成")
    }

    /**
     * 预算耗尽 → 空列表 **且**登记失败类型。
     *
     * 后半句才是关键：少了登记，`scanObjects` 会把空列表写进 `objects.queues`，
     * 导航树被清空而 UI 完全不知情（真相是「这次没读到」，不是「没有对象」）。
     */
    @Test
    fun `budget exceeded yields empty list and records the failed type`() = runBlocking {
        val failures = java.util.concurrent.ConcurrentHashMap.newKeySet<MqObjectType>()

        val rows = PcfObjectScanner().safelyList<MqObjectSnapshot>(MqObjectType.QUEUE, failures) {
            throw ScanBudgetExceededException(60_000)
        }

        assertTrue(rows.isEmpty(), "兜底应当给空列表，不往上抛")
        assertTrue(MqObjectType.QUEUE in failures, "失败类型必须被登记，否则缓存会被空列表覆盖")
    }

    @Test
    fun `ordinary failure also records the failed type`() = runBlocking {
        val failures = java.util.concurrent.ConcurrentHashMap.newKeySet<MqObjectType>()

        val snapshot = PcfObjectScanner().safelyNullable(MqObjectType.QUEUE_MANAGER, failures) {
            throw IllegalStateException("QMGR 返回了奇怪的东西")
        }

        assertNull(snapshot)
        assertTrue(MqObjectType.QUEUE_MANAGER in failures)
    }

    @Test
    fun `success records nothing`() = runBlocking {
        val failures = java.util.concurrent.ConcurrentHashMap.newKeySet<MqObjectType>()

        val rows = PcfObjectScanner().safelyList<MqObjectSnapshot>(MqObjectType.QUEUE, failures) { emptyList() }

        assertTrue(rows.isEmpty())
        assertTrue(failures.isEmpty(), "成功的类型不该被当成失败")
    }

    private fun scanResult(failedTypes: Set<MqObjectType>) = ScanResult(
        queueManager = null,
        queues = emptyList(),
        channels = emptyList(),
        topics = emptyList(),
        subscriptions = emptyList(),
        listeners = emptyList(),
        services = emptyList(),
        processes = emptyList(),
        namelists = emptyList(),
        authInfos = emptyList(),
        commInfos = emptyList(),
        failedTypes = failedTypes,
    )
}
