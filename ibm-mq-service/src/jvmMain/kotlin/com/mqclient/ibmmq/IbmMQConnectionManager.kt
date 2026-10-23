package com.mqclient.ibmmq

import com.ibm.mq.MQQueueManager
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.cache.CacheManager
import com.mqclient.core.concurrent.BlockingCalls
import com.mqclient.core.scheduler.ScheduledJob
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.ibmmq.event.IbmMQEvents
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * IBM MQ 连接的生命周期管理：**显式连接模型**的中枢。
 *
 * 与旧模型（连接新增就注册扫描任务、心跳隐式建连）的分界：
 *  - 程序启动 / `connection.added` 只 [ensureContext] —— 登记 DISCONNECTED 上下文，
 *    **不连接、不建 cache、不注册任何定时任务**；
 *  - 只有用户显式动作（连接按钮，将来的 queue/topic 点击）才 [connect]：
 *    真建 MQ 连接 → 建 cache → 按配置间隔注册扫描任务 → 报 CONNECTED；
 *  - [disconnect] / 删除 / 模块关闭：取消任务 → 关 MQ 句柄 → 清 cache → 报
 *    DISCONNECTED。状态事实同时喂 [ConnectionStore.reportStatus]（侧边栏）与
 *    [IbmMQConnectionContext.status]（模块内守卫），见 [setStatus]。
 *
 * 并发：同一连接的 connect / disconnect / 删除用**每连接一把 [Mutex]** 串行 ——
 * 两个入口同时点「连接」只有一方真的建连；不同连接互不阻塞。
 */
class IbmMQConnectionManager(
    private val context: ServiceContext,
    private val service: IbmMQService,
) {

    // 二次保障：模块初始化路径（IbmMQModule.initialize）已配过，这里兜底防直连绕过。
    init {
        IbmMQClientLogConfigurator.init()
    }

    private val contexts = ConcurrentHashMap<String, IbmMQConnectionContext>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /* ------------------------------ 查询 ------------------------------ */

    fun contextOrNull(connectionId: String): IbmMQConnectionContext? = contexts[connectionId]

    fun allContexts(): List<IbmMQConnectionContext> = contexts.values.toList()

    /* ------------------------------ 登记与注销 ------------------------------ */

    /**
     * 建立（或取回）一条连接的 DISCONNECTED 上下文：启动补登记与 `connection.added`
     * 共用。**只登记，不连接** —— cache、MQ 句柄、定时任务全部留给 [connect]。
     */
    fun ensureContext(connection: ConnectionInfo): IbmMQConnectionContext {
        var created = false
        val ctx = contexts.compute(connection.id) { id, existing ->
            if (existing == null) created = true
            existing ?: IbmMQConnectionContext(id, ibmMQConfigFromOptions(connection.options))
        }!!
        ibmMqLog.debug(
            "context ${if (created) "created" else "reused"}: connection=${connection.id} " +
                "qmgr=${ctx.config.queueManager}",
        )
        return ctx
    }

    /**
     * `connection.updated`：换配置快照。已连接 / 连接中的先断开 —— 配置变了继续用
     * 旧连接没有意义，下次显式连接自然用新配置。
     */
    fun updateContextConfig(connection: ConnectionInfo) {
        val ctx = contexts[connection.id] ?: run {
            ibmMqLog.debug("config update ignored (no context): connection=${connection.id}")
            return
        }
        ctx.config = ibmMQConfigFromOptions(connection.options)
        ibmMqLog.debug("context config updated: connection=${connection.id} qmgr=${ctx.config.queueManager}")
        if (ctx.status == ConnectionStatus.Connected || ctx.status == ConnectionStatus.Connecting) {
            ibmMqLog.info(
                "disconnecting active connection for config update: connection=${connection.id} status=${ctx.status}",
            )
            disconnectAsync(connection.id, reason = "config updated")
        }
    }

    /** `connection.deleted`：断开 + 摘除上下文（cache 随 [IbmMQConnectionContext.cleanup] 清空）。 */
    fun remove(connectionId: String) {
        ibmMqLog.debug("connection remove requested: connection=$connectionId")
        context.coroutineScope.launch {
            disconnectNow(connectionId, reason = "connection deleted")
            contexts.remove(connectionId)
        }
    }

    /* ------------------------------ 连接 / 断开 ------------------------------ */

    /**
     * 显式建立连接。DISCONNECTED / FAILED 才真正发起；CONNECTED 直接复用；
     * CONNECTING 忽略重复触发。连接成功后：建 cache → 注册定时任务（间隔来自
     * [IbmMQConnectionContext.config]）→ 报 CONNECTED。
     */
    suspend fun connect(connectionId: String): Result<Unit> = lockFor(connectionId).withLock {
        val ctx = contexts[connectionId]
            ?: return Result.failure(IllegalStateException("连接 $connectionId 未登记"))
        when (ctx.status) {
            ConnectionStatus.Connected -> return Result.success(Unit)
            ConnectionStatus.Connecting -> {
                ibmMqLog.info("connect ignored (already connecting): connection=$connectionId")
                return Result.failure(IllegalStateException("连接正在进行中"))
            }
            else -> Unit
        }

        val start = System.currentTimeMillis()
        ibmMqLog.debug(
            "connect start: connection=$connectionId qmgr=${ctx.config.queueManager} " +
                "timeout=${ctx.config.connectionTimeout}s",
        )
        // FAILED 状态下可能残留坏句柄：先悄悄扔掉，connect 只管建新的。
        closeQuietly(ctx)

        setStatus(ctx, ConnectionStatus.Connecting)
        ctx.lastError = null
        try {
            // 连接阶段超时（connectionTimeout 配置，默认 30s）：MQ 的 TCP 握手没有短超时，
            // 掉网时能挂十几分钟。`MQQueueManager` 的构造是**纯阻塞、无挂起点**的 ——
            // `withTimeout` 在它身上不生效（协程卡在里面检查不到取消），所以这里用
            // BlockingCalls：把构造丢到独立线程，调用方真正在 connectionTimeout 后拿回控制权。
            //
            // 超时后那条构造不会被打断（它仍会跑完）。迟到的句柄交给 onAbandoned 关掉 ——
            // 比之前的「delay(2×timeout) 后再去读变量」靠谱：不靠猜它什么时候回来。
            val manager = BlockingCalls.withDeadline(
                timeoutMs = ctx.config.connectionTimeout * 1_000L,
                label = "连接 ${ctx.config.queueManager}",
                onAbandoned = { late ->
                    runCatching { late.disconnect() }
                        .onFailure { error ->
                            ibmMqLog.warn(
                                "late connect handle close failed: connection=$connectionId error=$error",
                            )
                        }
                },
            ) {
                MQQueueManager(ctx.config.queueManager.trim(), mqProperties(ctx.config))
            }
            ctx.mqConnection = manager
            ctx.cache = CacheManager()
            setStatus(ctx, ConnectionStatus.Connected)
            registerScanJobs(ctx)
            context.emit(IbmMQEvents.CONNECTED, mapOf("connectionId" to connectionId))
            ibmMqLog.info(
                "connected: connection=$connectionId qmgr=${ctx.config.queueManager} " +
                    "duration=${System.currentTimeMillis() - start}ms",
            )
            Result.success(Unit)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            ctx.mqConnection = null
            ctx.cache = null
            ctx.lastError = error.message ?: error::class.simpleName ?: "unknown"
            setStatus(ctx, ConnectionStatus.Failed)
            context.emit(
                name = IbmMQEvents.CONNECT_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to ctx.lastError),
            )
            ibmMqLog.warn(
                "connect failed: connection=$connectionId reason=${ctx.lastError} " +
                    "duration=${System.currentTimeMillis() - start}ms",
            )
            Result.failure(error)
        }
    }

    /** UI / 删除链路的入口：fire-and-forget，结果走状态上报与事件，不阻塞点击。 */
    fun disconnectAsync(connectionId: String, reason: String? = null) {
        context.coroutineScope.launch { disconnectNow(connectionId, reason) }
    }

    /**
     * 断开：取消该连接全部定时任务 → 关 MQ 句柄 → 清 cache → 状态回 DISCONNECTED。
     * 已经是 DISCONNECTED 时是空操作（重复点「断开」无副作用）。
     */
    suspend fun disconnectNow(connectionId: String, reason: String? = null) =
        lockFor(connectionId).withLock {
            val ctx = contexts[connectionId] ?: return@withLock
            if (ctx.status == ConnectionStatus.Disconnected) return@withLock

            unregisterScanJobs(connectionId)
            // 手动刷新（UI 按钮）的协程也要收：它占着该连接的 scanLocks，句柄又要关了。
            service.cancelScan(connectionId)
            closeQuietly(ctx)
            ctx.cleanup()
            setStatus(ctx, ConnectionStatus.Disconnected)
            context.emit(
                name = IbmMQEvents.DISCONNECTED,
                payload = mapOf("connectionId" to connectionId, "reason" to (reason ?: "")),
            )
            ibmMqLog.info("disconnected: connection=$connectionId reason=${reason ?: "user"}")
        }

    /** 模块销毁：全部连接断开 + 注册表清空（顺序：先断连接再摘表）。 */
    suspend fun shutdownAll() {
        ibmMqLog.info("shutdown all connections: count=${contexts.size}")
        contexts.keys.toList().forEach { disconnectNow(it, reason = "module shutdown") }
        contexts.clear()
    }

    /* ------------------------------ 定时任务 ------------------------------ */

    /**
     * 按配置注册两条扫描任务 —— 间隔来自用户建连接时的配置，**不再硬编码**：
     *  - 消息数量（心跳）：`messageScanIntervalSeconds`（默认 10 秒，3-3600）；
     *  - 对象列表（慢扫）：`objectScanIntervalMinutes`（默认 60 分钟，1-1440），
     *    晚 5 秒起步，把首拍让给消息扫描。
     *
     * 幂等：同 ID 重复注册会顶掉旧的（见 JobScheduler），重连时直接重注册即可。
     */
    private fun registerScanJobs(ctx: IbmMQConnectionContext) {
        val scheduler = context.jobScheduler
        val config = ctx.config
        scheduler.schedule(
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, ctx.connectionId, JOB_SCAN_MESSAGES),
                intervalMs = config.messageScanIntervalSeconds * 1_000L,
                runOnStart = true,
                action = { service.scanMessageCounts(ctx.connectionId) },
            ),
        )
        scheduler.schedule(
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, ctx.connectionId, JOB_SCAN_OBJECTS),
                intervalMs = config.objectScanIntervalMinutes * 60_000L,
                initialDelayMs = OBJECTS_FIRST_DELAY_MS,
                action = { service.scanObjects(ctx.connectionId) },
            ),
        )
        ibmMqLog.info(
            "scan jobs registered: connection=${ctx.connectionId} " +
                "messages=${config.messageScanIntervalSeconds}s objects=${config.objectScanIntervalMinutes}min",
        )
    }

    /** 按前缀收掉该连接的全部定时任务（连接断开 / 删除）。 */
    private fun unregisterScanJobs(connectionId: String) {
        ibmMqLog.debug("scan jobs unregistering: connection=$connectionId")
        context.jobScheduler.cancelByPrefix(ScheduledJob.prefixFor(MODULE_ID, connectionId))
    }

    /* ------------------------------ 内部 ------------------------------ */

    /** 状态双写：模块内守卫（ctx.status）+ 核心事实（reportStatus → 侧边栏）。 */
    private fun setStatus(ctx: IbmMQConnectionContext, status: ConnectionStatus) {
        ctx.status = status
        ConnectionStore.reportStatus(ctx.connectionId, status, MODULE_ID)
    }

    private fun lockFor(connectionId: String): Mutex =
        locks.computeIfAbsent(connectionId) { Mutex() }

    /** 释放 MQ 句柄（阻塞 IO 切到 IO 线程）；失败只记日志 —— 断开路径不能被一个关不掉的句柄中断。 */
    private suspend fun closeQuietly(ctx: IbmMQConnectionContext) {
        val manager = ctx.mqConnection ?: return
        ctx.mqConnection = null
        try {
            withContext(Dispatchers.IO) { manager.disconnect() }
            ibmMqLog.debug("mq handle closed: connection=${ctx.connectionId}")
        } catch (error: Throwable) {
            ibmMqLog.warn("mq disconnect failed: connection=${ctx.connectionId} error=$error")
        }
    }
}
