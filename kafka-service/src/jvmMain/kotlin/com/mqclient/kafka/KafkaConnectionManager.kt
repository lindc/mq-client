package com.mqclient.kafka

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.cache.CacheManager
import com.mqclient.core.scheduler.ScheduledJob
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.DescribeClusterOptions

/**
 * Kafka 连接的生命周期管理：**显式连接模型**的中枢，与 ibm-mq 的
 * IbmMQConnectionManager 同构。
 *
 *  - 程序启动 / `connection.added` 只 [ensureContext] —— 登记 DISCONNECTED 上下文，
 *    **不连接**；
 *  - 用户显式动作才 [connect]：建 AdminClient → describeCluster 验证连通性 →
 *    建 cache → 按配置间隔注册扫描任务（对象慢扫 + Lag 心跳）→ 报 CONNECTED；
 *  - [disconnectNow] / 删除 / 模块关闭：取消扫描任务 → 关 AdminClient → 清缓存 →
 *    报 DISCONNECTED。状态事实同时喂 `ConnectionStore.reportStatus`（侧边栏）与
 *    [KafkaConnectionContext.status]（模块内守卫），见 [setStatus]。
 *
 * 并发：同一连接的 connect / disconnect / 删除用**每连接一把 [Mutex]** 串行；
 * 不同连接互不阻塞。
 *
 * Kafka 与 MQ 的连接差异：`AdminClient.create` 是惰性的（不真的建 TCP），真正的
 * 连通性验证要靠一次 API 调用 —— 这里用 `describeCluster`（还会顺带把 broker 列表
 * 拿到手，后续扫描直接复用）。超时由两处兜底：AdminClient 属性
 * （`request.timeout.ms` / `default.api.timeout.ms`，见 [KafkaConfig.toAdminProperties]）
 * 与 `KafkaFuture.get(timeout)` 本身。
 */
class KafkaConnectionManager(
    private val context: ServiceContext,
    private val service: KafkaScanService,
) {

    private val contexts = ConcurrentHashMap<String, KafkaConnectionContext>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /* ------------------------------ 查询 ------------------------------ */

    fun contextOrNull(connectionId: String): KafkaConnectionContext? = contexts[connectionId]

    fun allContexts(): List<KafkaConnectionContext> = contexts.values.toList()

    /* ------------------------------ 登记与注销 ------------------------------ */

    /**
     * 建立（或取回）一条连接的 DISCONNECTED 上下文：启动补登记与 `connection.added`
     * 共用。**只登记，不连接** —— AdminClient 留给 [connect]。
     */
    fun ensureContext(connection: ConnectionInfo): KafkaConnectionContext {
        var created = false
        val ctx = contexts.compute(connection.id) { id, existing ->
            if (existing == null) created = true
            existing ?: KafkaConnectionContext(id, kafkaConfigFromOptions(connection.options))
        }!!
        kafkaLog.debug(
            "context ${if (created) "created" else "reused"}: connection=${connection.id} " +
                "bootstrap=${ctx.config.bootstrapServers}",
        )
        return ctx
    }

    /**
     * `connection.updated`：换配置快照。已连接 / 连接中的先断开 —— 配置变了继续用
     * 旧连接没有意义，下次显式连接自然用新配置。
     */
    fun updateContextConfig(connection: ConnectionInfo) {
        val ctx = contexts[connection.id] ?: run {
            kafkaLog.debug("config update ignored (no context): connection=${connection.id}")
            return
        }
        ctx.config = kafkaConfigFromOptions(connection.options)
        kafkaLog.debug("context config updated: connection=${connection.id} bootstrap=${ctx.config.bootstrapServers}")
        if (ctx.status == ConnectionStatus.Connected || ctx.status == ConnectionStatus.Connecting) {
            kafkaLog.info(
                "disconnecting active connection for config update: connection=${connection.id} status=${ctx.status}",
            )
            disconnectAsync(connection.id, reason = "config updated")
        }
    }

    /** `connection.deleted`：断开 + 摘除上下文。 */
    fun remove(connectionId: String) {
        kafkaLog.debug("connection remove requested: connection=$connectionId")
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
                kafkaLog.info("connect ignored (already connecting): connection=$connectionId")
                return Result.failure(IllegalStateException("连接正在进行中"))
            }
            else -> Unit
        }

        val start = System.currentTimeMillis()
        kafkaLog.debug(
            "connect start: connection=$connectionId bootstrap=${ctx.config.bootstrapServers} " +
                "timeout=${ctx.config.connectionTimeout}s",
        )
        // FAILED 状态下可能残留坏句柄：先悄悄扔掉，connect 只管建新的。
        closeQuietly(ctx)

        setStatus(ctx, ConnectionStatus.Connecting)
        ctx.lastError = null
        try {
            ctx.adminClient = createVerifiedAdmin(ctx.config)
            // 缓存与扫描任务只属于活连接（与 ibm-mq 同一生命周期）。
            ctx.cache = CacheManager()
            setStatus(ctx, ConnectionStatus.Connected)
            registerScanJobs(ctx)
            context.emit(KafkaEvents.CONNECTED, mapOf("connectionId" to connectionId))
            kafkaLog.info(
                "connected: connection=$connectionId bootstrap=${ctx.config.bootstrapServers} " +
                    "duration=${System.currentTimeMillis() - start}ms",
            )
            Result.success(Unit)
        } catch (error: Throwable) {
            // 取消（模块 shutdown 等）必须原样上抛，否则取消语义被吞。
            // 句柄已由 createVerifiedAdmin 在失败路径自行关闭，这里只做记账。
            if (error is CancellationException) throw error
            ctx.adminClient = null
            ctx.lastError = errorMessage(error)
            setStatus(ctx, ConnectionStatus.Failed)
            context.emit(
                name = KafkaEvents.CONNECT_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to ctx.lastError),
            )
            kafkaLog.warn(
                "connect failed: connection=$connectionId reason=${ctx.lastError} " +
                    "duration=${System.currentTimeMillis() - start}ms",
            )
            Result.failure(error)
        }
    }

    /**
     * 「测试连接」：用给定配置发起一次**临时连接**（describeCluster 验证后立刻关掉，
     * 不影响任何正式连接的状态），失败抛异常 —— 成功返回即连通。成功路径也必须关：
     * 句柄是这里建的，丢弃不关等于每次测试泄漏一个连接。
     */
    suspend fun testConnection(config: KafkaConfig) {
        val admin = createVerifiedAdmin(config)
        try {
            // 验证通过即达目的；close 交给 IO 线程，与 closeQuietly 同一套纪律。
            withContext(Dispatchers.IO) { admin.close(Duration.ZERO) }
        } catch (error: Throwable) {
            kafkaLog.warn("test connection close failed: error=$error")
        }
    }

    /** UI / 删除链路的入口：fire-and-forget，结果走状态上报与事件，不阻塞点击。 */
    fun disconnectAsync(connectionId: String, reason: String? = null) {
        context.coroutineScope.launch { disconnectNow(connectionId, reason) }
    }

    /**
     * 断开：取消该连接全部定时任务 → 关 AdminClient → 清缓存 → 状态回 DISCONNECTED。
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
                name = KafkaEvents.DISCONNECTED,
                payload = mapOf("connectionId" to connectionId, "reason" to (reason ?: "")),
            )
            kafkaLog.info("disconnected: connection=$connectionId reason=${reason ?: "user"}")
        }

    /** 模块销毁：全部连接断开 + 注册表清空（顺序：先断连接再摘表）。 */
    suspend fun shutdownAll() {
        kafkaLog.info("shutdown all connections: count=${contexts.size}")
        contexts.keys.toList().forEach { disconnectNow(it, reason = "module shutdown") }
        contexts.clear()
    }

    /* ------------------------------ 定时任务 ------------------------------ */

    /**
     * 按配置注册两条扫描任务（与 ibm-mq 的 registerScanJobs 同构）—— 间隔来自用户
     * 建连接时的配置，**不再硬编码**：
     *  - Lag 心跳（快扫）：`lagScanIntervalSeconds`（默认 30 秒），runOnStart 立即首拍；
     *  - 对象（慢扫）：`objectScanIntervalMinutes`（默认 10 分钟），晚 5 秒起步，
     *    把首拍让给心跳。
     *
     * 幂等：同 ID 重复注册会顶掉旧的（见 JobScheduler），重连时直接重注册即可。
     */
    private fun registerScanJobs(ctx: KafkaConnectionContext) {
        val scheduler = context.jobScheduler
        val config = ctx.config
        scheduler.schedule(
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, ctx.connectionId, JOB_SCAN_LAG),
                intervalMs = config.lagScanIntervalSeconds * 1_000L,
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
        kafkaLog.info(
            "scan jobs registered: connection=${ctx.connectionId} " +
                "lag=${config.lagScanIntervalSeconds}s objects=${config.objectScanIntervalMinutes}min",
        )
    }

    /** 按前缀收掉该连接的全部定时任务（连接断开 / 删除）。 */
    private fun unregisterScanJobs(connectionId: String) {
        kafkaLog.debug("scan jobs unregistering: connection=$connectionId")
        context.jobScheduler.cancelByPrefix(ScheduledJob.prefixFor(MODULE_ID, connectionId))
    }

    /* ------------------------------ 内部 ------------------------------ */

    /**
     * 建 AdminClient 并用 describeCluster 验证连通性。AdminClient.create 只校验配置
     * （bootstrap 为空等在这里爆），真正的 TCP 连接发生在 describeCluster —— 超时由
     * 两处兜底：AdminClient 属性（request/default API timeout，见
     * [KafkaConfig.toAdminProperties]）与 `KafkaFuture.get(timeout)` 本身。
     *
     * 失败（含取消）时自己把刚建的句柄关掉再抛，成功时把可用的 AdminClient 交给调用方。
     */
    private suspend fun createVerifiedAdmin(config: KafkaConfig): AdminClient {
        val timeoutMs = config.connectionTimeout * 1_000L
        var admin: AdminClient? = null
        try {
            admin = withContext(Dispatchers.IO) { AdminClient.create(config.toAdminProperties()) }
            withContext(Dispatchers.IO) {
                admin.describeCluster(DescribeClusterOptions().timeoutMs(timeoutMs.toInt()))
                    .nodes()
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
            }
            return admin
        } catch (error: Throwable) {
            runCatching { admin?.close(Duration.ZERO) }
            throw error
        }
    }

    /** 状态双写：模块内守卫（ctx.status）+ 核心事实（reportStatus → 侧边栏）。 */
    private fun setStatus(ctx: KafkaConnectionContext, status: ConnectionStatus) {
        ctx.status = status
        ConnectionStore.reportStatus(ctx.connectionId, status, MODULE_ID)
    }

    private fun lockFor(connectionId: String): Mutex =
        locks.computeIfAbsent(connectionId) { Mutex() }

    /** 关 AdminClient（切到 IO 线程）；失败只记日志 —— 断开路径不能被一个关不掉的句柄中断。 */
    private suspend fun closeQuietly(ctx: KafkaConnectionContext) {
        val admin = ctx.adminClient ?: return
        ctx.adminClient = null
        try {
            withContext(Dispatchers.IO) { admin.close(Duration.ZERO) }
            kafkaLog.debug("admin client closed: connection=${ctx.connectionId}")
        } catch (error: Throwable) {
            kafkaLog.warn("admin client close failed: connection=${ctx.connectionId} error=$error")
        }
    }

    /** 给用户的失败原因：解掉 ExecutionException 包装，拿根因的消息。 */
    private fun errorMessage(error: Throwable): String =
        (error as? ExecutionException)?.cause?.message
            ?: error.message
            ?: error::class.simpleName
            ?: "unknown"
}
