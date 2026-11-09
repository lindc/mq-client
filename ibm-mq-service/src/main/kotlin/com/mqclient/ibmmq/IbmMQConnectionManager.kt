package com.mqclient.ibmmq

import com.ibm.mq.MQQueueManager
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.concurrent.BlockingCalls
import com.mqclient.core.scheduler.ScheduledJob
import com.mqclient.core.spi.ServiceContext
import com.mqclient.ibmmq.event.IbmMQEvents
import com.mqclient.mq.base.AbstractConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * IBM MQ 连接的生命周期管理：**显式连接模型**的中枢。
 *
 * 生命周期模板（状态机 / 每连接锁 / 事件 / 扫描任务）全部在
 * [AbstractConnectionManager]，本类只提供 IBM MQ 特有部分：
 *
 *  - [open]：建 `MQQueueManager`。**唯一的特殊之处**是 MQ 的 TCP 握手没有短超时
 *    （掉网时能挂十几分钟），而构造是纯阻塞、无挂起点的调用 —— 所以要走
 *    [BlockingCalls.withDeadline]，超时后把迟到的句柄交给 `onAbandoned` 关掉；
 *  - [closeResources]：`disconnect()`，且必须 `NonCancellable`（见其 KDoc）；
 *  - 扫描任务：消息数量心跳（runOnStart）+ 对象慢扫（晚 5 秒起步）。
 *
 * 注意：本模块的扫描失败**仍按旧语义**在 `IbmMQService` 里直接置 Failed（没有接入
 * 基类的连续失败阈值）—— 这是行为差异，重构阶段不夹带行为变更。
 */
class IbmMQConnectionManager(
    context: ServiceContext,
    private val service: IbmMQService,
) : AbstractConnectionManager<IbmMQConnectionContext>(context, MODULE_ID) {

    // 二次保障：模块初始化路径（IbmMQModule.initialize）已配过，这里兜底防直连绕过。
    init {
        IbmMQClientLogConfigurator.init()
    }

    /* ------------------------------ 基类钩子 ------------------------------ */

    override fun createContext(connectionId: String, connection: ConnectionInfo): IbmMQConnectionContext =
        IbmMQConnectionContext(connectionId, ibmMQConfigFromOptions(connection.options))

    override fun applyConfig(ctx: IbmMQConnectionContext, connection: ConnectionInfo) {
        ctx.config = ibmMQConfigFromOptions(connection.options)
    }

    override fun describe(ctx: IbmMQConnectionContext): String = "qmgr=${ctx.config.queueManager}"

    /**
     * 建 MQ 连接。`MQQueueManager` 的构造是**纯阻塞、无挂起点**的 —— `withTimeout`
     * 在它身上不生效（协程卡在里面检查不到取消），所以用 [BlockingCalls]：把构造丢到
     * 独立线程，调用方真正在 connectionTimeout 后拿回控制权。
     *
     * 超时后那条构造不会被打断（它仍会跑完）。迟到的句柄交给 `onAbandoned` 关掉 ——
     * 比「delay(2×timeout) 后再去读变量」靠谱：不靠猜它什么时候回来。
     *
     * TLS（[IbmMqTls.withTls]）：证书只能经进程级 JSSE 属性注入，所以整个构造过程要在
     * 「写本连接证书 → 建连 → 还原」的窗口里完成，且与其他连接互斥。**测试连接走同一个
     * 窗口**（见 IbmMQService.testConnection），两条路径的 TLS 行为因此完全一致。
     */
    override suspend fun open(ctx: IbmMQConnectionContext) {
        ctx.mqConnection = IbmMqTls.withTls(ctx.config) {
            BlockingCalls.withDeadline(
                timeoutMs = ctx.config.connectionTimeout * 1_000L,
                label = "连接 ${ctx.config.queueManager}",
                onAbandoned = { late ->
                    runCatching { late.disconnect() }
                        .onFailure { error ->
                            log.warn("late connect handle close failed: connection=${ctx.connectionId} error=$error")
                        }
                },
            ) {
                MQQueueManager(ctx.config.queueManager.trim(), mqProperties(ctx.config))
            }
        }
    }

    override suspend fun onBeforeDisconnect(ctx: IbmMQConnectionContext) {
        // 手动刷新（UI 按钮）的协程也要收：它占着该连接的 MQI 锁，句柄又要关了。
        service.cancelScan(ctx.connectionId)
        // 「部分数据未刷新」的旧结论不能诈尸到重连后（新连接可能权限不同）。
        IbmMQModule.clearScanFailures(ctx.connectionId)
    }

    override fun isHandlePresent(ctx: IbmMQConnectionContext): Boolean = ctx.mqConnection != null

    override val connectedEvent: String = IbmMQEvents.CONNECTED
    override val connectFailedEvent: String = IbmMQEvents.CONNECT_FAILED
    override val disconnectedEvent: String = IbmMQEvents.DISCONNECTED

    /**
     * 消息数量（心跳，runOnStart）+ 对象列表（慢扫，晚 5 秒起步把首拍让给心跳）。
     * 间隔来自用户建连接时的配置，**不硬编码**。
     */
    override fun scanJobs(ctx: IbmMQConnectionContext): List<ScheduledJob> {
        val config = ctx.config
        return listOf(
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, ctx.connectionId, JOB_SCAN_MESSAGES),
                intervalMs = config.messageScanIntervalSeconds * 1_000L,
                runOnStart = true,
                action = { service.scanMessageCounts(ctx.connectionId) },
            ),
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, ctx.connectionId, JOB_SCAN_OBJECTS),
                intervalMs = config.objectScanIntervalMinutes * 60_000L,
                initialDelayMs = OBJECTS_FIRST_DELAY_MS,
                action = { service.scanObjects(ctx.connectionId) },
            ),
        )
    }

    /* ------------------------------ 内部 ------------------------------ */

    /**
     * 释放 MQ 句柄（阻塞 IO 切到 IO 线程）；失败只记日志 —— 断开路径不能被一个关不掉的
     * 句柄中断。
     *
     * `NonCancellable` 是必需的：块体先 `ctx.mqConnection = null` 再断开，若 `withContext`
     * 在**入口**就因取消而抛，`disconnect()` 一次都不会执行，而句柄引用已经丢了 —— 这个
     * 连接就永远泄漏到进程退出（GC 也不是确定性的）。断开本来就是不可回退的，做了就要做完。
     */
    override suspend fun closeResources(ctx: IbmMQConnectionContext) {
        val manager = ctx.mqConnection ?: return
        // 与在飞的 MQI 串行：扫描 / 浏览 / 发送 / MQSC 都持这把锁，它们与
        // `disconnect()` 同时作用于同一个 `MQQueueManager` 是未定义行为（实测会冒
        // MQRC 2009 / 连接被意外拆断）。[onBeforeDisconnect] 只取消定时任务，取消
        // 打不断 worker 上已开始的阻塞 MQI 调用。
        //
        // 有界等待，不能无限等：一条卡在 socket 上的 MQI 会让 disconnect 永远排队，
        // 而断开是用户明确要求的动作，必须完成。拿不到锁就记一笔，然后照常断开。
        val serialized = awaitMqiQuiescence(ctx.mqiLock, DISCONNECT_LOCK_WAIT_MS)
        if (!serialized) {
            log.warn(
                "disconnect proceeding without MQI lock after ${DISCONNECT_LOCK_WAIT_MS}ms: " +
                    "connection=${ctx.connectionId} (an in-flight MQI call is still running)",
            )
        }
        ctx.mqConnection = null
        try {
            withContext(NonCancellable + Dispatchers.IO) { manager.disconnect() }
            log.debug("mq handle closed: connection=${ctx.connectionId}")
        } catch (cancellation: CancellationException) {
            // 取消原样上抛：句柄已释放，但仍然不能让取消语义被吞。
            throw cancellation
        } catch (error: Throwable) {
            log.warn("mq disconnect failed: connection=${ctx.connectionId} error=$error")
        }
    }

    private companion object {
        /**
         * 断开前等 `mqiLock` 的上限。
         *
         * 只用于「让在飞的 MQI 调用先收尾」，不用于无限期串行：拿不到就断开，
         * 比把退出/删除路径一起拖死更诚实。10s 远大于任何正常 MQI 往返。
         */
        const val DISCONNECT_LOCK_WAIT_MS = 10_000L
    }
}
