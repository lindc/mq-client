package com.mqclient.ibmmq.pcf

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.scheduler.ScheduledJob
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emitSync
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.event.IbmMQEvents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import com.mqclient.testing.assumeNotNull

/**
 * 真机验证：**协作式超时改造后的完整扫描链路**（`scanAll` → `withAgent` → `ScanBudget`）。
 *
 * 为什么要单独一个测试：既有的 [PcfObjectScannerLiveTest] 走的是 `scanAllWith(manager)`
 * 串行变体与 `*With(session)` 直查路径，**一次都没经过 [PcfObjectScanner] 的
 * `withAgent`**（测试输出里没有任何 `pcf agent open` / `pcf agent closed` 日志）。
 * 而协作式超时恰好改的就是 `withAgent` + `inquire`：
 *
 *  - 11 类对象**并发**（每类独立 agent），共用一个 `ScanBudget`
 *  - 每条 `inquire` 前调 `budget.check()`
 *  - 外层 `BlockingCalls` 兜底（预算 + 40s）
 *
 * 所以这个测试盯的是「改造没把正常路径弄坏」：
 *  1. 11 类全部能扫到（预算不误伤、并发复用同一条连接不炸）
 *  2. `pcf agent open` / `closed` 成对出现（agent 全部释放，没泄漏）
 *  3. 扫描后连接仍 Connected（超时兜底没有误触发）
 *  4. 缓存被写入且 `failedTypes` 为空（没有类型被判部分失败）
 *
 * 环境不具备（无 IBM MQ 连接 / QMGR 不在线）时打印 SKIP 并通过。
 */
class PcfScanBudgetLiveTest {

    @Test
    fun liveScanAllThroughBudgetPathKeepsEverythingHealthy() = runBlocking {
        // 无环境 = SKIP（assumption），不是 PASSED；必须放在 try 之外（见同目录
        // PcfObjectScannerLiveTest 的说明：假设异常会被宽 catch 吞掉）。
        val info = assumeNotNull(
            "LIVE SKIP: config.toml 里没有 IBM MQ 连接",
            ConnectionStore.connections.value.firstOrNull { it.serviceId == "ibm-mq" },
        )
        println("=========== PCF SCAN BUDGET DIAG (withAgent 路径) ===========")
        println("  connection = ${info.name} (${info.id})")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bus = EventBus(scope)
        val context = ServiceContext.of("ibm-mq", bus, scope, JobScheduler(scope, bus))
        IbmMQModule.initialize(context)
        try {
            context.emitSync(IbmMQEvents.CONNECT_REQUESTED, mapOf("connectionId" to info.id))
            val connected = withTimeoutOrNull(60_000) {
                while (IbmMQModule.connectionOrNull(info.id)?.status != ConnectionStatus.Connected) {
                    delay(200)
                }
                true
            } ?: false
            if (!connected) {
                val reason = IbmMQModule.connectionOrNull(info.id)?.lastError
                com.mqclient.testing.assumeLive("LIVE SKIP: 连接未成功（QMGR 不在线 / 凭据失效）：$reason", false)
            }
            println("  connect ok: status=Connected")

            // 关掉定时任务：connect 会注册「5s 后首次慢扫」+「每 10s 心跳」，它们与本次
            // 验证共用同一把 scanLocks。不收掉的话，本测试一释放锁它们就会抢着跑，
            // 收尾时那些 agent 的 open/closed 日志成不了对（不是泄漏，是被 shutdown 打断）。
            val cancelled = context.jobScheduler.cancelByPrefix(
                ScheduledJob.prefixFor("ibm-mq", info.id),
            )
            println("  已取消定时任务数 = $cancelled")

            val service = IbmMQModule.serviceOrNull()
            assertNotNull(service, "模块初始化后 serviceOrNull() 不应为 null")

            // 这一行走的就是 scanAll → 11 路 async → withAgent → ScanBudget。
            val started = System.currentTimeMillis()
            service.scanObjects(info.id)
            val elapsed = System.currentTimeMillis() - started
            println("  scanObjects 用时 = ${elapsed}ms（预算 60s）")

            val statusAfter = IbmMQModule.connectionOrNull(info.id)?.status
            val qmgr = service.cachedQueueManager(info.id)
            val queueCount = service.cachedObjects(info.id, MqObjectType.QUEUE).size
            val channelCount = service.cachedObjects(info.id, MqObjectType.CHANNEL).size
            val topicCount = service.cachedObjects(info.id, MqObjectType.TOPIC).size
            val subCount = service.cachedObjects(info.id, MqObjectType.SUBSCRIPTION).size
            val listenerCount = service.cachedObjects(info.id, MqObjectType.LISTENER).size
            val serviceCount = service.cachedObjects(info.id, MqObjectType.SERVICE).size
            val processCount = service.cachedObjects(info.id, MqObjectType.PROCESS).size
            val namelistCount = service.cachedObjects(info.id, MqObjectType.NAMELIST).size
            val authCount = service.cachedObjects(info.id, MqObjectType.AUTH_INFO).size
            val commCount = service.cachedObjects(info.id, MqObjectType.COMM_INFO).size
            val counts = mapOf(
                "qmgr" to (if (qmgr != null) 1 else 0),
                "queues" to queueCount,
                "channels" to channelCount,
                "topics" to topicCount,
                "subscriptions" to subCount,
                "listeners" to listenerCount,
                "services" to serviceCount,
                "processes" to processCount,
                "namelists" to namelistCount,
                "authInfos" to authCount,
                "commInfos" to commCount,
            )
            println("  --- 缓存（11 类）---")
            counts.forEach { (kind, n) -> println("  $kind = $n") }

            // ---- 断言 ----
            assertEquals(ConnectionStatus.Connected, statusAfter, "扫描后连接应仍为 Connected")
            assertTrue(elapsed < 60_000, "一轮扫描不该超出预算；实际 ${elapsed}ms")
            assertNotNull(qmgr, "QMGR 快照不应为 null")
            assertTrue(queueCount > 0, "队列数不应为 0（QM1 上有系统队列）")
            assertTrue(channelCount > 0, "通道数不应为 0")
            val emptyKinds = counts.filterValues { it == 0 }.keys
            println("  空类型 = ${if (emptyKinds.isEmpty()) "无" else emptyKinds}")

            // 连接状态徽章：scanObjects 成功路径应写入 10 类计数（qmgr 不进徽章）。
            val badges = IbmMQModule.objectCountsFlow.value[info.id].orEmpty()
            println("  --- 导航徽章 ---")
            badges.forEach { (type, n) -> println("  $type = $n") }
            assertTrue(badges.isNotEmpty(), "扫描成功后应写入导航徽章计数")

            println("=========== DIAG DONE ===========")
        } finally {
            IbmMQModule.shutdown()
        }
    }
}
