package com.mqclient.mq.base

import com.mqclient.core.ServiceException

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.cache.CacheManager
import com.mqclient.core.concurrent.KeyedMutex
import com.mqclient.core.log.Log
import com.mqclient.core.log.ModuleLogger
import com.mqclient.core.scheduler.ScheduledJob
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * MQ 模块连接生命周期的**唯一实现**（显式连接模型的中枢）。
 *
 * ## 为什么要有这个基类
 *
 * IBM MQ / Kafka / RabbitMQ / RocketMQ 四个 `*ConnectionManager` 曾经各自维护一份
 * 逐字重复的生命周期：`ensureContext` / `updateContextConfig` / `remove` / `connect`
 * 的状态机 / `disconnectNow` 的收尾顺序 / `registerScanJobs` / `onScanFailed` 的阈值 /
 * `setStatus` 的双写。四份副本带来的是**定向漂移**：Kafka 与 RocketMQ 加了「连续失败
 * 阈值 + 自愈」，RabbitMQ 追加过一次，而 IBM MQ 至今还是「失败一次即判死」——
 * 不是设计选择，是复制粘贴漏改。把模板下沉后，这类修正只需要做一次。
 *
 * ## 模板方法一览
 *
 * ```
 * connect(id)                         disconnectNow(id)                 shutdownAll()
 *  ├─ 状态守卫（Connected/Connecting）  ├─ unregisterScanJobs              └─ 逐个 disconnectNow
 *  ├─ closeResources（清残留）          ├─ onBeforeDisconnect（模块清理）
 *  ├─ setStatus(Connecting)            ├─ closeResources（关句柄）
 *  ├─ open(ctx)  ← 子类：建句柄+验证    ├─ ctx.cleanup()（清缓存/计数）
 *  ├─ ctx.cache = CacheManager()       ├─ setStatus(Disconnected)
 *  ├─ onConnected(ctx) ← 子类钩子      └─ emit(disconnected)
 *  ├─ setStatus(Connected)
 *  ├─ registerScanJobs(ctx) ← 子类给任务
 *  └─ emit(connected)
 * ```
 *
 * ## 并发
 *
 * 同一连接的 `connect` / `disconnectNow` / `remove` 用**每连接一把锁**串行
 * （[KeyedMutex]：不同连接互不阻塞）。子类需要同一把锁做别的串行（例如 RabbitMQ 的
 * 共享 AMQP 连接懒建）时用 [withConnectionLock] —— **不要**在已持有该锁的路径里再调它
 * （重入会死锁，与 Mutex 语义一致）。
 *
 * ## 子类必须保证的两件事
 *
 * 1. [open] 失败时**自己关掉刚建的句柄**再抛（基类看不到半成品句柄）；
 * 2. [closeResources] 自己兜异常（只记日志）—— 断开路径不能被一个关不掉的句柄中断。
 *
 * @param C 本模块的连接上下文类型。
 */
abstract class AbstractConnectionManager<C : AbstractConnectionContext<*>>(
    protected val context: ServiceContext,
    /** 模块 id：任务 id 前缀、`ConnectionStore.reportStatus` 的 source、日志 tag。 */
    protected val moduleId: String,
) {

    /** 模块日志句柄（tag = [moduleId]）。 */
    protected val log: ModuleLogger = Log.withTag(moduleId)

    private val contexts = java.util.concurrent.ConcurrentHashMap<String, C>()
    private val locks = KeyedMutex()

    /* ------------------------------ 子类钩子 ------------------------------ */

    /** 从连接配置快照建上下文（只建对象，不建连接）。 */
    protected abstract fun createContext(connectionId: String, connection: ConnectionInfo): C

    /**
     * 建立句柄并**验证连通性**（`describeCluster` / `overview` / `startAndVerify`…）。
     *
     * 约定：失败（含取消）时把刚建的句柄关掉再抛 —— 基类拿不到半成品句柄。
     */
    protected abstract suspend fun open(ctx: C)

    /** 关闭 [open] 建出来的全部句柄；必须自行兜异常（失败只记日志）。 */
    protected abstract suspend fun closeResources(ctx: C)

    /** 该连接要注册的扫描任务（任务 id 由基类按 `moduleId.connectionId.*` 拼）。 */
    protected abstract fun scanJobs(ctx: C): List<ScheduledJob>

    /** 连接成功且 cache 已创建后、置 Connected **之前**的钩子（复位句柄槽 / 写元信息）。 */
    protected open suspend fun onConnected(ctx: C) {}

    /** 断开时、关句柄**之前**的模块侧清理（取消手动扫描 / 清「部分刷新」结论 / 清扫描状态）。 */
    protected open suspend fun onBeforeDisconnect(ctx: C) {}

    /** 句柄是否还在（扫描失败阈值用它区分「真没连接」与「扫描请求失败」）。 */
    protected abstract fun isHandlePresent(ctx: C): Boolean

    /** 日志里的连接标识（各协议不同：qmgr / bootstrap / host:vhost / namesrv）。 */
    protected open fun describe(ctx: C): String = ctx.connectionId

    /** 连接 / 断开 / 连接失败事件名（各模块自己的事件常量）。 */
    protected abstract val connectedEvent: String
    protected abstract val connectFailedEvent: String
    protected abstract val disconnectedEvent: String

    /**
     * 异常 → 用户可读文案。默认取 message / 类名；Kafka / RocketMQ 覆盖它来解
     * `ExecutionException` 包装。
     */
    protected open fun errorMessage(error: Throwable): String =
        error.message ?: error::class.simpleName ?: "unknown"

    /* ------------------------------ 查询 ------------------------------ */

    fun contextOrNull(connectionId: String): C? = contexts[connectionId]

    fun allContexts(): List<C> = contexts.values.toList()

    /** 在**该连接的锁**内执行 [block]（与 connect / disconnect 互斥）。 */
    protected suspend fun <T> withConnectionLock(connectionId: String, block: suspend () -> T): T =
        locks.withLock(connectionId, block)

    /* ------------------------------ 登记与注销 ------------------------------ */

    /**
     * 建立（或取回）一条连接的 DISCONNECTED 上下文：启动补登记与 `connection.added`
     * 共用。**只登记，不连接** —— 句柄、缓存、定时任务全部留给 [connect]。
     */
    fun ensureContext(connection: ConnectionInfo): C {
        var created = false
        val ctx = contexts.compute(connection.id) { id, existing ->
            if (existing == null) created = true
            existing ?: createContext(id, connection)
        }!!
        log.debug("context ${if (created) "created" else "reused"}: connection=${connection.id} (${describe(ctx)})")
        return ctx
    }

    /**
     * `connection.updated`：换配置快照。已连接 / 连接中的先断开 —— 配置变了继续用
     * 旧连接没有意义，下次显式连接自然用新配置。
     */
    fun updateContextConfig(connection: ConnectionInfo) {
        val ctx = contexts[connection.id] ?: run {
            log.debug("config update ignored (no context): connection=${connection.id}")
            return
        }
        applyConfig(ctx, connection)
        log.debug("context config updated: connection=${connection.id} (${describe(ctx)})")
        if (ctx.status == ConnectionStatus.Connected || ctx.status == ConnectionStatus.Connecting) {
            log.info("disconnecting active connection for config update: connection=${connection.id} status=${ctx.status}")
            disconnectAsync(connection.id, reason = "config updated")
        }
    }

    /**
     * 把 [connection] 的新配置快照写进上下文（`connection.updated`）。
     *
     * 刻意做成抽象而不是让基类重新调 [createContext]：那样会白建一个上下文对象，
     * 而且「配置解析」这条路径必须在 `ensureContext` 与 `update` 之间保持**同一份实现**
     * （子类一行 `ctx.config = xxxConfigFromOptions(connection.options)` 即可）。
     */
    protected abstract fun applyConfig(ctx: C, connection: ConnectionInfo)

    /** `connection.deleted`：断开 + 摘除上下文。 */
    fun remove(connectionId: String) {
        log.debug("connection remove requested: connection=$connectionId")
        context.coroutineScope.launch {
            disconnectNow(connectionId, reason = "connection deleted")
            contexts.remove(connectionId)
        }
    }

    /* ------------------------------ 连接 / 断开 ------------------------------ */

    /**
     * 显式建立连接。DISCONNECTED / FAILED 才真正发起；CONNECTED 直接复用；
     * CONNECTING 忽略重复触发。
     */
    suspend fun connect(connectionId: String): Result<Unit> = locks.withLock(connectionId) {
        val ctx = contexts[connectionId]
            ?: return@withLock Result.failure(ServiceException(
                "err_conn_not_registered_id",
                args = listOf(connectionId),
                fallbackMessage = "Connection '%1\$s' is not registered",
            ))
        when (ctx.status) {
            ConnectionStatus.Connected -> return@withLock Result.success(Unit)
            ConnectionStatus.Connecting -> {
                log.info("connect ignored (already connecting): connection=$connectionId")
                return@withLock Result.failure(ServiceException(
                "err_conn_in_progress",
                fallbackMessage = "A connection attempt is already in progress",
            ))
            }
            else -> Unit
        }

        val start = System.currentTimeMillis()
        log.debug("connect start: connection=$connectionId (${describe(ctx)})")
        // FAILED 状态下可能残留坏句柄：先悄悄扔掉，connect 只管建新的。
        closeResources(ctx)

        setStatus(ctx, ConnectionStatus.Connecting)
        ctx.lastError = null
        try {
            open(ctx)
            // 缓存与扫描任务只属于活连接。
            ctx.cache = CacheManager()
            // 注意顺序：onConnected 必须在 setStatus(Connected) **之前** —— 读路径可能
            // 先看到 Connected，再撞上仍未就绪的句柄槽，拿到一个误导性的「已断开」。
            onConnected(ctx)
            setStatus(ctx, ConnectionStatus.Connected)
            registerScanJobs(ctx)
            context.emit(connectedEvent, mapOf("connectionId" to connectionId))
            log.info(
                "connected: connection=$connectionId (${describe(ctx)}) " +
                    "duration=${System.currentTimeMillis() - start}ms",
            )
            Result.success(Unit)
        } catch (error: Throwable) {
            // 取消（模块 shutdown 等）必须原样上抛，否则取消语义被吞。
            if (error is CancellationException) throw error
            ctx.lastError = errorMessage(error)
            ctx.cache = null
            setStatus(ctx, ConnectionStatus.Failed)
            context.emit(
                name = connectFailedEvent,
                payload = mapOf("connectionId" to connectionId, "message" to ctx.lastError),
            )
            log.warn(
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
     * 断开：取消定时任务 → 模块清理 → 关句柄 → 清缓存 → 状态回 DISCONNECTED。
     * 已经是 DISCONNECTED 时是空操作（重复点「断开」无副作用）。
     *
     * **不取 [withConnectionLock] 以外的锁**：所有句柄关闭都在这里串行完成，子类的
     * `closeResources` 也不应再等业务锁（等锁等于把断开路径一起拖死）。
     */
    suspend fun disconnectNow(connectionId: String, reason: String? = null) =
        locks.withLock(connectionId) {
            val ctx = contexts[connectionId] ?: return@withLock
            if (ctx.status == ConnectionStatus.Disconnected) return@withLock

            unregisterScanJobs(connectionId)
            onBeforeDisconnect(ctx)
            closeResources(ctx)
            ctx.cleanup()
            setStatus(ctx, ConnectionStatus.Disconnected)
            context.emit(
                name = disconnectedEvent,
                payload = mapOf("connectionId" to connectionId, "reason" to (reason ?: "")),
            )
            log.info("disconnected: connection=$connectionId reason=${reason ?: "user"}")
        }

    /** 模块销毁：全部连接断开 + 注册表清空（顺序：先断连接再摘表）。 */
    suspend fun shutdownAll() {
        log.info("shutdown all connections: count=${contexts.size}")
        contexts.keys.toList().forEach { disconnectNow(it, reason = "module shutdown") }
        contexts.clear()
    }

    /* ------------------------------ 定时任务 ------------------------------ */

    /**
     * 注册子类给出的扫描任务。任务 id 由子类用 `ScheduledJob.idFor(moduleId, …)` 拼好。
     *
     * 幂等：同 ID 重复注册会顶掉旧的（见 JobScheduler），重连时直接重注册即可。
     */
    private fun registerScanJobs(ctx: C) {
        val jobs = scanJobs(ctx)
        jobs.forEach { context.jobScheduler.schedule(it) }
        log.info("scan jobs registered: connection=${ctx.connectionId} count=${jobs.size}")
    }

    /** 按前缀收掉该连接的全部定时任务（连接断开 / 删除）。 */
    private fun unregisterScanJobs(connectionId: String) {
        log.debug("scan jobs unregistering: connection=$connectionId")
        context.jobScheduler.cancelByPrefix(ScheduledJob.prefixFor(moduleId, connectionId))
    }

    /* ------------------------------ 扫描健康度 ------------------------------ */

    /**
     * 定时扫描的一次失败：**连续** [scanFailureThreshold] 次才把连接判为 Failed。
     *
     * 返回 true 表示这次失败**触发了**状态翻转（用于日志区分「记了一笔」与「判死了」）。
     */
    fun onScanFailed(connectionId: String, message: String): Boolean {
        val ctx = contexts[connectionId] ?: return false
        // 没有句柄就是真的没连接（或已断开），交给 connect 路径，不走阈值。
        if (!isHandlePresent(ctx)) return false
        val failures = ctx.recordScanFailure()
        if (failures < SCAN_FAILURE_THRESHOLD) {
            log.info(
                "scan failure tolerated: connection=$connectionId " +
                    "consecutive=$failures/$SCAN_FAILURE_THRESHOLD reason=$message",
            )
            return false
        }
        if (ctx.status == ConnectionStatus.Failed) return false
        ctx.lastError = message
        setStatus(ctx, ConnectionStatus.Failed)
        log.warn("connection marked FAILED after $failures consecutive scan failures: connection=$connectionId reason=$message")
        return true
    }

    /**
     * 定时扫描成功：计数归零；如果之前被判为 Failed（但句柄还在），**自动恢复**成
     * Connected。自愈的前提是扫描入口不再看 status（只看句柄在不在）。
     */
    fun onScanSucceeded(connectionId: String) {
        val ctx = contexts[connectionId] ?: return
        val previous = ctx.scanFailureCount()
        ctx.resetScanFailures()
        if (ctx.status == ConnectionStatus.Failed && isHandlePresent(ctx)) {
            ctx.lastError = null
            setStatus(ctx, ConnectionStatus.Connected)
            log.info("connection recovered after $previous consecutive scan failures: connection=$connectionId")
        }
    }

    /** **受保护**的状态写入口：模块内守卫（ctx.status）+ 核心事实（侧边栏）。 */
    protected fun setStatus(ctx: C, status: ConnectionStatus) {
        ctx.status = status
        ConnectionStore.reportStatus(ctx.connectionId, status, moduleId)
    }

    companion object {
        /**
         * 连续扫描失败阈值：所有 MQ 模块统一（见 [onScanFailed]）。
         *
         * 公开出来是为了让各模块的 `SCAN_FAILURE_THRESHOLD` 常量指向它 —— 测试断言
         * 各模块常量时，等于同时断言这里。
         */
        const val SCAN_FAILURE_THRESHOLD: Int = 3
    }
}
