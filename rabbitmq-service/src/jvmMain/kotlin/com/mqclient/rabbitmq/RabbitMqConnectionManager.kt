package com.mqclient.rabbitmq

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.cache.CacheManager
import com.mqclient.core.scheduler.ScheduledJob
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * RabbitMQ 连接的生命周期管理：**显式连接模型**的中枢，与 KafkaConnectionManager 同构。
 *
 *  - 程序启动 / `connection.added` 只 [ensureContext] —— 登记 DISCONNECTED 上下文，
 *    **不连接**；
 *  - 用户显式动作才 [connect]：建管理 API 客户端 → `GET /api/overview` 验证连通 →
 *    建 cache → 按配置间隔注册扫描任务（对象慢扫 + 状态快扫）→ 报 CONNECTED；
 *  - [disconnectNow] / 删除 / 模块关闭：取消扫描任务 → 关 HTTP 客户端 → 清缓存 →
 *    报 DISCONNECTED。状态事实同时喂 `ConnectionStore.reportStatus`（侧边栏）与
 *    [RabbitMqConnectionContext.status]（模块内守卫），见 [setStatus]。
 *
 * 并发：同一连接的 connect / disconnect / 删除用**每连接一把 [Mutex]** 串行；
 * 不同连接互不阻塞。
 *
 * 与 Kafka 的差异：没有惰性句柄——HttpClient 建立本身不连网，「验证连通」就是
 * 第一次真实请求（overview）。超时由 [RabbitMqAdminClient] 内的 HttpTimeout 兜底。
 * 第一阶段无常驻 AMQP 连接（消息收发在后续阶段引入，届时这里挂第二个句柄）。
 */
class RabbitMqConnectionManager(
    private val context: ServiceContext,
    private val service: RabbitMqScanService,
) {

    private val contexts = java.util.concurrent.ConcurrentHashMap<String, RabbitMqConnectionContext>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /* ------------------------------ 查询 ------------------------------ */

    fun contextOrNull(connectionId: String): RabbitMqConnectionContext? = contexts[connectionId]

    fun allContexts(): List<RabbitMqConnectionContext> = contexts.values.toList()

    /* ------------------------------ 登记与注销 ------------------------------ */

    /**
     * 建立（或取回）一条连接的 DISCONNECTED 上下文：启动补登记与 `connection.added`
     * 共用。**只登记，不连接** —— HTTP 客户端留给 [connect]。
     */
    fun ensureContext(connection: ConnectionInfo): RabbitMqConnectionContext {
        var created = false
        val ctx = contexts.compute(connection.id) { id, existing ->
            if (existing == null) created = true
            existing ?: RabbitMqConnectionContext(id, rabbitMqConfigFromOptions(connection.options))
        }!!
        rabbitMqLog.debug(
            "context ${if (created) "created" else "reused"}: connection=${connection.id} " +
                "host=${ctx.config.host}:${ctx.config.managementPort} vhost=${ctx.config.vhost}",
        )
        return ctx
    }

    /**
     * `connection.updated`：换配置快照。已连接 / 连接中的先断开 —— 配置变了继续用
     * 旧连接没有意义，下次显式连接自然用新配置。
     */
    fun updateContextConfig(connection: ConnectionInfo) {
        val ctx = contexts[connection.id] ?: run {
            rabbitMqLog.debug("config update ignored (no context): connection=${connection.id}")
            return
        }
        ctx.config = rabbitMqConfigFromOptions(connection.options)
        rabbitMqLog.debug(
            "context config updated: connection=${connection.id} host=${ctx.config.host}:${ctx.config.managementPort}",
        )
        if (ctx.status == ConnectionStatus.Connected || ctx.status == ConnectionStatus.Connecting) {
            rabbitMqLog.info(
                "disconnecting active connection for config update: connection=${connection.id} status=${ctx.status}",
            )
            disconnectAsync(connection.id, reason = "config updated")
        }
    }

    /** `connection.deleted`：断开 + 摘除上下文。 */
    fun remove(connectionId: String) {
        rabbitMqLog.debug("connection remove requested: connection=$connectionId")
        context.coroutineScope.launch {
            disconnectNow(connectionId, reason = "connection deleted")
            contexts.remove(connectionId)
        }
    }

    /* ------------------------------ 连接 / 断开 ------------------------------ */

    /**
     * 显式建立连接。DISCONNECTED / FAILED 才真正发起；CONNECTED 直接复用；
     * CONNECTING 忽略重复触发。连接成功后报 CONNECTED。
     */
    suspend fun connect(connectionId: String): Result<Unit> = lockFor(connectionId).withLock {
        val ctx = contexts[connectionId]
            ?: return Result.failure(IllegalStateException("连接 $connectionId 未登记"))
        when (ctx.status) {
            ConnectionStatus.Connected -> return Result.success(Unit)
            ConnectionStatus.Connecting -> {
                rabbitMqLog.info("connect ignored (already connecting): connection=$connectionId")
                return Result.failure(IllegalStateException("连接正在进行中"))
            }
            else -> Unit
        }

        val start = System.currentTimeMillis()
        rabbitMqLog.debug(
            "connect start: connection=$connectionId host=${ctx.config.host}:${ctx.config.managementPort} " +
                "vhost=${ctx.config.vhost} timeout=${ctx.config.connectionTimeout}s",
        )
        // FAILED 状态下可能残留坏句柄：先悄悄扔掉，connect 只管建新的。
        closeQuietly(ctx)

        setStatus(ctx, ConnectionStatus.Connecting)
        ctx.lastError = null
        try {
            val (admin, clusterInfo) = createVerifiedAdmin(ctx.config)
            ctx.admin = admin
            // 缓存与扫描任务只属于活连接（与 kafka / ibm-mq 同一生命周期）。
            ctx.cache = CacheManager()
            // 集群元信息（clusterName / 版本 / 节点）随建连验证顺带入缓存 —— 概览页
            // 顶部的集群信息从这里读，不再单独发请求。永不过期（TTL 0）：生命周期
            // 由整个缓存托管（断开即清）。
            ctx.cache?.put(KEY_CLUSTER_INFO, clusterInfo, ttlMs = 0L)
            setStatus(ctx, ConnectionStatus.Connected)
            registerScanJobs(ctx)
            context.emit(RabbitMqEvents.CONNECTED, mapOf("connectionId" to connectionId))
            rabbitMqLog.info(
                "connected: connection=$connectionId host=${ctx.config.host}:${ctx.config.managementPort} " +
                    "duration=${System.currentTimeMillis() - start}ms",
            )
            Result.success(Unit)
        } catch (error: Throwable) {
            // 取消（模块 shutdown 等）必须原样上抛，否则取消语义被吞。
            if (error is CancellationException) throw error
            ctx.admin = null
            ctx.lastError = errorMessage(error)
            setStatus(ctx, ConnectionStatus.Failed)
            context.emit(
                name = RabbitMqEvents.CONNECT_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to ctx.lastError),
            )
            rabbitMqLog.warn(
                "connect failed: connection=$connectionId reason=${ctx.lastError} " +
                    "duration=${System.currentTimeMillis() - start}ms",
            )
            Result.failure(error)
        }
    }

    /**
     * 「测试连接」：用给定配置发起一次**临时连接**（overview 验证后立刻关掉，不影响
     * 任何正式连接的状态），失败抛异常 —— 成功返回即连通。成功路径也必须关：句柄是
     * 这里建的，丢弃不关等于每次测试泄漏一个客户端。
     */
    suspend fun testConnection(config: RabbitMqConfig) {
        val (admin, _) = createVerifiedAdmin(config)
        try {
            admin.close()
        } catch (error: Throwable) {
            rabbitMqLog.warn("test connection close failed: error=$error")
        }
    }

    /** UI / 删除链路的入口：fire-and-forget，结果走状态上报与事件，不阻塞点击。 */
    fun disconnectAsync(connectionId: String, reason: String? = null) {
        context.coroutineScope.launch { disconnectNow(connectionId, reason) }
    }

    /**
     * 断开：取消该连接全部定时任务 → 关 HTTP 客户端 → 清缓存 → 状态回 DISCONNECTED。
     * 已经是 DISCONNECTED 时是空操作（重复点「断开」无副作用）。
     */
    suspend fun disconnectNow(connectionId: String, reason: String? = null) =
        lockFor(connectionId).withLock {
            val ctx = contexts[connectionId] ?: return@withLock
            if (ctx.status == ConnectionStatus.Disconnected) return@withLock

            unregisterScanJobs(connectionId)
            // 手动刷新（UI 按钮）的协程也要收：它占着该连接的 scanLocks，句柄又要关了。
            service.cancelScan(connectionId)
            // 「部分数据未刷新」的旧结论不能诈尸到重连后（新连接可能权限不同）。
            RabbitMqModule.clearScanFailures(connectionId)
            closeQuietly(ctx)
            ctx.cleanup()
            setStatus(ctx, ConnectionStatus.Disconnected)
            context.emit(
                name = RabbitMqEvents.DISCONNECTED,
                payload = mapOf("connectionId" to connectionId, "reason" to (reason ?: "")),
            )
            rabbitMqLog.info("disconnected: connection=$connectionId reason=${reason ?: "user"}")
        }

    /** 模块销毁：全部连接断开 + 注册表清空（顺序：先断连接再摘表）。 */
    suspend fun shutdownAll() {
        rabbitMqLog.info("shutdown all connections: count=${contexts.size}")
        contexts.keys.toList().forEach { disconnectNow(it, reason = "module shutdown") }
        contexts.clear()
    }

    /* ------------------------------ 定时任务 ------------------------------ */

    /**
     * 按配置注册两条扫描任务（与 kafka 的 registerScanJobs 同构）—— 间隔来自用户
     * 建连接时的配置，**不再硬编码**：
     *  - 状态快扫：`statusScanIntervalSeconds`（默认 10 秒），runOnStart 立即首拍；
     *  - 对象慢扫：`objectScanIntervalMinutes`（默认 5 分钟），晚 5 秒起步，把首拍
     *    让给快扫。
     *
     * 幂等：同 ID 重复注册会顶掉旧的（见 JobScheduler），重连时直接重注册即可。
     */
    private fun registerScanJobs(ctx: RabbitMqConnectionContext) {
        val scheduler = context.jobScheduler
        val config = ctx.config
        scheduler.schedule(
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, ctx.connectionId, JOB_SCAN_STATUS),
                intervalMs = config.statusScanIntervalSeconds * 1_000L,
                runOnStart = true,
                action = { service.scanStatus(ctx.connectionId) },
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
        rabbitMqLog.info(
            "scan jobs registered: connection=${ctx.connectionId} " +
                "status=${config.statusScanIntervalSeconds}s objects=${config.objectScanIntervalMinutes}min",
        )
    }

    /** 按前缀收掉该连接的全部定时任务（连接断开 / 删除）。 */
    private fun unregisterScanJobs(connectionId: String) {
        rabbitMqLog.debug("scan jobs unregistering: connection=$connectionId")
        context.jobScheduler.cancelByPrefix(ScheduledJob.prefixFor(MODULE_ID, connectionId))
    }

    /* ------------------------------ 内部 ------------------------------ */

    /**
     * 建管理 API 客户端并用 `GET /api/overview` 验证连通性（认证 + 地址 + vhost 一次
     * 验完）。失败（含取消）时自己把刚建的客户端关掉再抛，成功时把客户端与集群元
     * 信息（clusterName / 版本 / 节点）一起交给调用方。错误文案已在
     * [RabbitMqAdminClient] 里转成人类可读。
     */
    private suspend fun createVerifiedAdmin(
        config: RabbitMqConfig,
    ): Pair<RabbitMqAdminClient, RabbitMqClusterInfoSnapshot> {
        var client: RabbitMqAdminClient? = null
        try {
            client = withContext(Dispatchers.IO) { RabbitMqAdminClient(config) }
            val overview = withContext(Dispatchers.IO) { client.overview() }
            val info = RabbitMqClusterInfoSnapshot(
                clusterName = overview.clusterName,
                rabbitmqVersion = overview.rabbitmqVersion,
                node = overview.node,
            )
            rabbitMqLog.info(
                "management api verified: cluster=${info.clusterName} " +
                    "rabbitmq=${info.rabbitmqVersion} node=${info.node}",
            )
            return client to info
        } catch (error: Throwable) {
            runCatching { client?.close() }
            throw error
        }
    }

    /** 状态双写：模块内守卫（ctx.status）+ 核心事实（reportStatus → 侧边栏）。 */
    private fun setStatus(ctx: RabbitMqConnectionContext, status: ConnectionStatus) {
        ctx.status = status
        ConnectionStore.reportStatus(ctx.connectionId, status, MODULE_ID)
    }

    /**
     * 定时扫描的一次失败：**连续** [SCAN_FAILURE_THRESHOLD] 次才把连接判为 Failed。
     *
     * 单次失败不再判死（旧行为）：管理 API 经代理时常会撞上被回收的空闲 socket，
     * 那是一次可恢复的请求失败，不是连接死亡。只有连续多轮都读不通才当作真断。
     *
     * 返回 true 表示这次失败**触发了**状态翻转（用于日志区分「记了一笔」与「判死了」）。
     */
    fun onScanFailed(connectionId: String, message: String): Boolean {
        val ctx = contexts[connectionId] ?: return false
        // 没有客户端就是真的没连接（或已断开），交给 connect 路径，不走阈值。
        if (ctx.admin == null) return false
        val failures = ctx.recordScanFailure()
        if (failures < SCAN_FAILURE_THRESHOLD) {
            rabbitMqLog.info(
                "scan failure tolerated: connection=$connectionId " +
                    "consecutive=$failures/$SCAN_FAILURE_THRESHOLD reason=$message",
            )
            return false
        }
        if (ctx.status == ConnectionStatus.Failed) return false
        ctx.lastError = message
        setStatus(ctx, ConnectionStatus.Failed)
        rabbitMqLog.warn(
            "connection marked FAILED after $failures consecutive scan failures: " +
                "connection=$connectionId reason=$message",
        )
        return true
    }

    /**
     * 定时扫描成功：计数归零；如果之前被判为 Failed（但客户端还在），**自动恢复**
     * 成 Connected。
     *
     * 这是整套修复的自愈环节：判死之后扫描仍会继续跑（扫描入口只看客户端在不在，
     * 不看 status），所以第一次成功就是恢复时机 —— 不需要用户手点重连，也不需要
     * 额外的定时器。
     */
    fun onScanSucceeded(connectionId: String) {
        val ctx = contexts[connectionId] ?: return
        val previous = ctx.scanFailureCount()
        ctx.resetScanFailures()
        if (ctx.status == ConnectionStatus.Failed && ctx.admin != null) {
            ctx.lastError = null
            setStatus(ctx, ConnectionStatus.Connected)
            rabbitMqLog.info(
                "connection recovered after $previous consecutive scan failures: connection=$connectionId",
            )
        }
    }

    private fun lockFor(connectionId: String): Mutex =
        locks.computeIfAbsent(connectionId) { Mutex() }

    /** 关 HTTP 客户端（切到 IO 线程）；失败只记日志 —— 断开路径不能被一个关不掉的句柄中断。 */
    private suspend fun closeQuietly(ctx: RabbitMqConnectionContext) {
        val admin = ctx.admin ?: return
        ctx.admin = null
        try {
            withContext(Dispatchers.IO) { admin.close() }
            rabbitMqLog.debug("management api client closed: connection=${ctx.connectionId}")
        } catch (error: Throwable) {
            rabbitMqLog.warn("management api client close failed: connection=${ctx.connectionId} error=$error")
        }
    }

    /** 给用户的失败原因：RabbitMqAdminClient 的 IllegalStateException 已是可读文案，直接取 message。 */
    private fun errorMessage(error: Throwable): String =
        error.message
            ?: error::class.simpleName
            ?: "unknown"
}
