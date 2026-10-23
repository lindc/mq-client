package com.mqclient.rocketmq

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.cache.CacheManager
import com.mqclient.core.scheduler.ScheduledJob
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * RocketMQ 连接的生命周期管理：**显式连接模型**的中枢，与 kafka 的
 * KafkaConnectionManager 同构。
 *
 *  - 程序启动 / `connection.added` 只 [ensureContext] —— 登记 DISCONNECTED 上下文，
 *    **不连接**；
 *  - 用户显式动作才 [connect]：建 AdminExt → start + examineBrokerClusterInfo 验证
 *    → 建 cache → 按配置间隔注册扫描任务（对象慢扫 + Lag 心跳）→ 报 CONNECTED；
 *  - [disconnectNow] / 删除 / 模块关闭：取消扫描任务 → 关 AdminExt → 清缓存 →
 *    报 DISCONNECTED。状态事实同时喂 `ConnectionStore.reportStatus`（侧边栏）与
 *    [RocketMqConnectionContext.status]（模块内守卫），见 [setStatus]。
 *
 * 并发：同一连接的 connect / disconnect / 删除用**每连接一把 [Mutex]** 串行；
 * 不同连接互不阻塞。
 *
 * RocketMQ 与 Kafka 的连接差异：DefaultMQAdminExt.start() 会真的连 NameServer
 * （启动钉子线程），验证靠 start 后的 examineBrokerClusterInfo —— 超时由构造参数
 * （timeoutMillis）兜底。
 */
class RocketMqConnectionManager(
    private val context: ServiceContext,
    private val service: RocketMqScanService,
) {

    private val contexts = ConcurrentHashMap<String, RocketMqConnectionContext>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /* ------------------------------ 查询 ------------------------------ */

    fun contextOrNull(connectionId: String): RocketMqConnectionContext? = contexts[connectionId]

    fun allContexts(): List<RocketMqConnectionContext> = contexts.values.toList()

    /* ------------------------------ 登记与注销 ------------------------------ */

    /** 建立（或取回）一条连接的 DISCONNECTED 上下文：**只登记，不连接**。 */
    fun ensureContext(connection: ConnectionInfo): RocketMqConnectionContext {
        var created = false
        val ctx = contexts.compute(connection.id) { id, existing ->
            if (existing == null) created = true
            existing ?: RocketMqConnectionContext(id, rocketMqConfigFromOptions(connection.options))
        }!!
        rocketMqLog.debug(
            "context " + (if (created) "created" else "reused") + ": connection=" + connection.id +
                " namesrv=" + ctx.config.namesrvAddr,
        )
        return ctx
    }

    /** `connection.updated`：换配置快照。已连接 / 连接中的先断开（配置变了旧连接无意义）。 */
    fun updateContextConfig(connection: ConnectionInfo) {
        val ctx = contexts[connection.id] ?: return
        ctx.config = rocketMqConfigFromOptions(connection.options)
        if (ctx.status == ConnectionStatus.Connected || ctx.status == ConnectionStatus.Connecting) {
            disconnectAsync(connection.id, reason = "config updated")
        }
    }

    /** `connection.deleted`：断开 + 摘除上下文。 */
    fun remove(connectionId: String) {
        context.coroutineScope.launch {
            disconnectNow(connectionId, reason = "connection deleted")
            contexts.remove(connectionId)
        }
    }

    /* ------------------------------ 连接 / 断开 ------------------------------ */

    /** 显式建立连接。DISCONNECTED / FAILED 才真正发起；CONNECTED 复用；CONNECTING 忽略。 */
    suspend fun connect(connectionId: String): Result<Unit> = lockFor(connectionId).withLock {
        val ctx = contexts[connectionId]
            ?: return Result.failure(IllegalStateException("连接 $connectionId 未登记"))
        when (ctx.status) {
            ConnectionStatus.Connected -> return Result.success(Unit)
            ConnectionStatus.Connecting -> {
                rocketMqLog.info("connect ignored (already connecting): connection=$connectionId")
                return Result.failure(IllegalStateException("连接正在进行中"))
            }
            else -> Unit
        }

        val start = System.currentTimeMillis()
        closeQuietly(ctx)

        setStatus(ctx, ConnectionStatus.Connecting)
        ctx.lastError = null
        try {
            ctx.adminClient = createVerifiedAdmin(ctx.config)
            // 缓存与扫描任务只属于活连接（与 kafka / ibm-mq 同一生命周期）。
            ctx.cache = CacheManager()
            setStatus(ctx, ConnectionStatus.Connected)
            registerScanJobs(ctx)
            context.emit(RocketMqEvents.CONNECTED, mapOf("connectionId" to connectionId))
            rocketMqLog.info(
                "connected: connection=$connectionId namesrv=" + ctx.config.namesrvAddr +
                    " duration=" + (System.currentTimeMillis() - start) + "ms",
            )
            Result.success(Unit)
        } catch (error: Throwable) {
            // 取消（模块 shutdown 等）必须原样上抛；句柄已在失败路径自行关闭。
            if (error is CancellationException) throw error
            ctx.adminClient = null
            ctx.lastError = errorMessage(error)
            setStatus(ctx, ConnectionStatus.Failed)
            context.emit(
                name = RocketMqEvents.CONNECT_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to ctx.lastError),
            )
            rocketMqLog.warn(
                "connect failed: connection=$connectionId reason=" + ctx.lastError +
                    " duration=" + (System.currentTimeMillis() - start) + "ms",
            )
            Result.failure(error)
        }
    }

    /** 「测试连接」：临时连接（start + 验证后立刻关掉），失败抛异常。 */
    suspend fun testConnection(config: RocketMqConfig) {
        val admin = createVerifiedAdmin(config)
        try {
            withContext(Dispatchers.IO) { admin.close() }
        } catch (error: Throwable) {
            rocketMqLog.warn("test connection close failed: error=$error")
        }
    }

    /** UI / 删除链路的入口：fire-and-forget，结果走状态上报与事件，不阻塞点击。 */
    fun disconnectAsync(connectionId: String, reason: String? = null) {
        context.coroutineScope.launch { disconnectNow(connectionId, reason) }
    }

    /** 断开：取消扫描任务 → 关 AdminExt → 清缓存 → 状态回 DISCONNECTED。 */
    suspend fun disconnectNow(connectionId: String, reason: String? = null) =
        lockFor(connectionId).withLock {
            val ctx = contexts[connectionId] ?: return@withLock
            if (ctx.status == ConnectionStatus.Disconnected) return@withLock

            unregisterScanJobs(connectionId)
            // 手动刷新（UI 按钮）的协程也要收：它占着该连接的 scanLocks，句柄又要关了。
            service.cancelScan(connectionId)
            closeQuietly(ctx)
            ctx.cleanup()
            // 扫描完成标记与缓存同生命周期：断开清掉，重连后重新等首扫。
            service.clearScanState(connectionId)
            // 「部分数据未刷新」的旧结论同样不能诈尸到重连后（新连接可能权限不同）。
            RocketMqModule.clearScanFailures(connectionId)
            setStatus(ctx, ConnectionStatus.Disconnected)
            context.emit(
                name = RocketMqEvents.DISCONNECTED,
                payload = mapOf("connectionId" to connectionId, "reason" to (reason ?: "")),
            )
            rocketMqLog.info("disconnected: connection=$connectionId reason=" + (reason ?: "user"))
        }

    /** 模块销毁：全部连接断开 + 注册表清空（顺序：先断连接再摘表）。 */
    suspend fun shutdownAll() {
        rocketMqLog.info("shutdown all connections: count=" + contexts.size)
        contexts.keys.toList().forEach { disconnectNow(it, reason = "module shutdown") }
        contexts.clear()
    }

    /* ------------------------------ 定时任务 ------------------------------ */

    /** 按配置注册两条扫描任务（与 kafka 同构）：快扫 runOnStart、慢扫晚 5 秒起步。 */
    private fun registerScanJobs(ctx: RocketMqConnectionContext) {
        val scheduler = context.jobScheduler
        val config = ctx.config
        scheduler.schedule(
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, ctx.connectionId, JOB_SCAN_LAG),
                intervalMs = config.statusScanIntervalSeconds * 1_000L,
                runOnStart = true,
                action = { service.scanGroupLags(ctx.connectionId) },
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
        rocketMqLog.info(
            "scan jobs registered: connection=" + ctx.connectionId +
                " status=" + config.statusScanIntervalSeconds + "s objects=" +
                config.objectScanIntervalMinutes + "min",
        )
    }

    /** 按前缀收掉该连接的全部定时任务（连接断开 / 删除）。 */
    private fun unregisterScanJobs(connectionId: String) {
        context.jobScheduler.cancelByPrefix(ScheduledJob.prefixFor(MODULE_ID, connectionId))
    }

    /* ------------------------------ 内部 ------------------------------ */

    /**
     * 建 AdminExt 并用 examineBrokerClusterInfo 验证连通性。失败（含取消）时自己把
     * 刚建的句柄关掉再抛，成功时把可用的 AdminClient 交给调用方。
     */
    private suspend fun createVerifiedAdmin(config: RocketMqConfig): RocketMqAdminClient {
        val client = RocketMqAdminClient(config)
        try {
            client.startAndVerify()
            return client
        } catch (error: Throwable) {
            runCatching { client.close() }
            throw error
        }
    }

    /** 状态双写：模块内守卫（ctx.status）+ 核心事实（reportStatus → 侧边栏）。 */
    private fun setStatus(ctx: RocketMqConnectionContext, status: ConnectionStatus) {
        ctx.status = status
        ConnectionStore.reportStatus(ctx.connectionId, status, MODULE_ID)
    }

    private fun lockFor(connectionId: String): Mutex =
        locks.computeIfAbsent(connectionId) { Mutex() }

    /** 关 AdminExt（切到 IO 线程）；失败只记日志 —— 断开路径不能被一个关不掉的句柄中断。 */
    private suspend fun closeQuietly(ctx: RocketMqConnectionContext) {
        val admin = ctx.adminClient ?: return
        ctx.adminClient = null
        try {
            withContext(Dispatchers.IO) { admin.close() }
            rocketMqLog.debug("admin client closed: connection=" + ctx.connectionId)
        } catch (error: Throwable) {
            rocketMqLog.warn("admin client close failed: connection=" + ctx.connectionId + " error=$error")
        }
    }

    /** 给用户的失败原因：解掉 ExecutionException 包装，拿根因的消息。 */
    private fun errorMessage(error: Throwable): String =
        (error as? ExecutionException)?.cause?.message
            ?: error.message
            ?: error::class.simpleName
            ?: "unknown"
}
