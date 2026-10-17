package com.mqclient.rabbitmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * RabbitMQ 对象 / 状态扫描：定时快扫 / 慢扫 + 缓存写入 + 事件通知，与 kafka 的
 * KafkaScanService 同一套模式（write-through 缓存、事件驱动 UI、失败分级）。
 *
 *  - **对象慢扫** [scanObjects]（默认 5min）：exchanges / queues（静态属性）/
 *    bindings 三类**并发**查询，全量替换写缓存 —— 全量替换的语义即「顺带清理
 *    已删除的对象」。任一类失败整轮失败（管理 API 是同一个端口的同一套权限，
 *    单类失败大概率意味着连接已断或权限缺口）。
 *  - **状态快扫** [scanStatus]（默认 10s）：`GET /api/queues` 的计数字段全量替换
 *    进 `queueStatus`，发事件通知 UI 刷新队列卡片。跑通 → reportStatus(Connected)
 *    保活（与 Kafka 的 lag 心跳同款）。
 *
 * 缓存：该连接专属的 [com.mqclient.core.cache.CacheManager]（挂
 * [RabbitMqConnectionContext.cache]，连接成功创建、断开清空）。key 约定：
 * `exchanges` / `queues` / `bindings` / `queueStatus`。读口 [cachedQueues] 等
 * 只查缓存、不碰 HTTP、不挂起。
 *
 * 事件：完成 / 失败都发（RabbitMqEvents.OBJECTS_SCANNED 等），订阅方（UI）读缓存
 * 刷新。全程不碰 UI 线程（HTTP 调用在 Dispatchers.IO）。
 */

/** 缓存 key：集群元信息（建连时 overview 的投影，永不过期、断开随缓存清空）。
 *  文件级 internal：RabbitMqConnectionManager 建连时也要写它。 */
internal const val KEY_CLUSTER_INFO = "clusterInfo"

class RabbitMqScanService(private val context: ServiceContext) {

    private companion object {
        /** 缓存 key：交换机列表（慢扫）。 */
        const val KEY_EXCHANGES = "exchanges"

        /** 缓存 key：队列列表（慢扫，静态属性）。 */
        const val KEY_QUEUES = "queues"

        /** 缓存 key：绑定关系（慢扫；拓扑图的关系来源）。 */
        const val KEY_BINDINGS = "bindings"

        /** 缓存 key：队列状态（快扫，Map<队列名, 状态快照>）。 */
        const val KEY_QUEUE_STATUS = "queueStatus"

        /** 慢扫数据的 TTL 倍率：慢扫间隔 × 1.5 —— 失联后数据别永远烂在缓存里的兜底。 */
        const val OBJECTS_TTL_FACTOR = 1.5f

        /** 快扫数据的 TTL 倍率：快扫间隔 × 2。 */
        const val STATUS_TTL_FACTOR = 2f
    }

    /** 同一模块内多次扫描串行执行，避免重叠扫描互相覆盖结果（与 kafka 的 scanLock 同职责）。 */
    private val scanLock = Mutex()

    // ---------------------------------------------------------------- 对象慢扫

    /**
     * 定时任务入口（`rabbitmq.<connectionId>.scan.objects`）：一轮并发扫齐 exchanges /
     * queues / bindings 三类并全量替换。任一失败整轮失败（见类 KDoc 的取舍）。
     */
    suspend fun scanObjects(connectionId: String) = scanLock.withLock {
        val ctx = connectedContext(connectionId) ?: return@withLock
        val admin = ctx.admin ?: return@withLock
        try {
            val objectsTtl = objectsTtlMs(ctx)
            val (exchangeDtos, queueDtos, bindingDtos) = withContext(Dispatchers.IO) {
                coroutineScope {
                    val exchanges = async { admin.exchanges() }
                    val queues = async { admin.queues() }
                    val bindings = async { admin.bindings() }
                    Triple(exchanges.await(), queues.await(), bindings.await())
                }
            }

            val exchangeSnapshots = exchangeDtos
                .map { RabbitMqExchangeSnapshot(it.name, it.type, it.durable, it.autoDelete, it.internal) }
                .sortedBy { it.name }
            val queueSnapshots = queueDtos
                .map { RabbitMqQueueSnapshot(it.name, it.stateText, it.durable, it.autoDelete, it.exclusive) }
                .sortedBy { it.name }
            val bindingSnapshots = bindingDtos
                .map {
                    RabbitMqBindingSnapshot(
                        source = it.source,
                        destination = it.destination,
                        destinationType = it.destinationType,
                        routingKey = it.routingKey,
                        arguments = it.arguments,
                    )
                }
                .sortedWith(compareBy({ it.source }, { it.destination }, { it.routingKey }))

            val cache = ctx.cache
            cache?.put(KEY_EXCHANGES, exchangeSnapshots, ttlMs = objectsTtl)
            cache?.put(KEY_QUEUES, queueSnapshots, ttlMs = objectsTtl)
            cache?.put(KEY_BINDINGS, bindingSnapshots, ttlMs = objectsTtl)

            context.emit(
                name = RabbitMqEvents.OBJECTS_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "exchangeCount" to exchangeSnapshots.size,
                    "queueCount" to queueSnapshots.size,
                    "bindingCount" to bindingSnapshots.size,
                ),
            )
            rabbitMqLog.info(
                "objects scanned: connection=$connectionId exchanges=${exchangeSnapshots.size} " +
                    "queues=${queueSnapshots.size} bindings=${bindingSnapshots.size}",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val message = errorMessage(error)
            rabbitMqLog.error("objects scan failed: connection=$connectionId error=$message")
            context.emit(
                name = RabbitMqEvents.OBJECTS_SCAN_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to message),
            )
            markFailed(connectionId, message)
        }
    }

    // ---------------------------------------------------------------- 状态快扫

    /**
     * 定时任务入口（`rabbitmq.<connectionId>.scan.status`）：`GET /api/queues` 的
     * 计数字段全量替换进 `queueStatus`。跑通即保活（reportStatus Connected，核心侧
     * 状态没变时直接忽略）。
     */
    suspend fun scanStatus(connectionId: String) = scanLock.withLock {
        val ctx = connectedContext(connectionId) ?: return@withLock
        val admin = ctx.admin ?: return@withLock
        try {
            val queueDtos = withContext(Dispatchers.IO) { admin.queues() }
            val status = queueDtos.associate { dto ->
                dto.name to RabbitMqQueueStatusSnapshot(
                    name = dto.name,
                    state = dto.stateText,
                    messages = dto.messages,
                    messagesReady = dto.messagesReady,
                    messagesUnacknowledged = dto.messagesUnacknowledged,
                    consumers = dto.consumers,
                )
            }

            ctx.cache?.put(KEY_QUEUE_STATUS, status, ttlMs = statusTtlMs(ctx))

            context.emit(
                name = RabbitMqEvents.STATUS_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "queueCount" to status.size,
                    "totalMessages" to status.values.sumOf { it.messages },
                ),
            )
            // 心跳跑通了即这条连接是活的，按「连接状态上报」约定报给核心（唯一入口，
            // 见 ConnectionStore.reportStatus）。状态没变时核心直接忽略。
            ConnectionStore.reportStatus(connectionId, ConnectionStatus.Connected, context.moduleId)
            rabbitMqLog.debug(
                "status scanned: connection=$connectionId queues=${status.size} " +
                    "totalMessages=${status.values.sumOf { it.messages }}",
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val message = errorMessage(error)
            rabbitMqLog.warn("status scan failed: connection=$connectionId error=$message")
            context.emit(
                name = RabbitMqEvents.STATUS_SCAN_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to message),
            )
            markFailed(connectionId, message)
        }
    }

    // ---------------------------------------------------------------- 只读口（UI）

    /** 队列列表（慢扫快照，静态属性）；缓存未就绪 / 过期返回空表。 */
    fun cachedQueues(connectionId: String): List<RabbitMqQueueSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_QUEUES) ?: emptyList()

    /** 交换机列表（慢扫快照）。 */
    fun cachedExchanges(connectionId: String): List<RabbitMqExchangeSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_EXCHANGES) ?: emptyList()

    /** 绑定关系（慢扫快照）。 */
    fun cachedBindings(connectionId: String): List<RabbitMqBindingSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_BINDINGS) ?: emptyList()

    /** 队列状态（快扫快照，按队列名索引）。 */
    fun cachedQueueStatus(connectionId: String): Map<String, RabbitMqQueueStatusSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_QUEUE_STATUS) ?: emptyMap()

    /** 集群元信息（建连时入缓存）；未就绪为 null。 */
    fun cachedClusterInfo(connectionId: String): RabbitMqClusterInfoSnapshot? =
        cacheOf(connectionId)?.getAs(KEY_CLUSTER_INFO)

    // ---------------------------------------------------------------- 交换机管理（实时写）

    /**
     * 创建交换机：admin PUT → 成功后同步把新交换机补进缓存（不等 5min 慢扫）→
     * 发 OBJECTS_SCANNED 驱动 UI 刷新。
     */
    suspend fun createExchange(
        connectionId: String,
        name: String,
        type: String,
        durable: Boolean,
        autoDelete: Boolean,
    ) {
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.admin ?: throw IllegalStateException("未连接")
        withContext(Dispatchers.IO) { admin.createExchange(name, type, durable, autoDelete) }
        // 同步补缓存：新交换机立即出现在列表（OBJECTS_SCANNED 事件驱动 UI 重读）。
        ctx.cache?.let { cache ->
            val current = cache.getAs<List<RabbitMqExchangeSnapshot>>(KEY_EXCHANGES).orEmpty()
                .filter { it.name != name }
            cache.put(
                KEY_EXCHANGES,
                (current + RabbitMqExchangeSnapshot(name, type, durable, autoDelete, internal = false))
                    .sortedBy { it.name },
                ttlMs = objectsTtlMs(ctx),
            )
        }
        context.emit(
            name = RabbitMqEvents.OBJECTS_SCANNED,
            payload = mapOf("connectionId" to connectionId),
        )
        rabbitMqLog.info("exchange created: connection=$connectionId name=$name type=$type")
    }

    /**
     * 删除交换机：默认交换机与 amq.* 在这里守卫（UI 侧动作也不显示，双保险）；成功后
     * 同步剪掉缓存里的交换机与其绑定的边 → 发 OBJECTS_SCANNED 驱动 UI 刷新。
     */
    suspend fun deleteExchange(connectionId: String, name: String) {
        require(name.isNotEmpty() && !name.startsWith("amq.")) {
            "默认交换机与系统交换机（amq.*）不可删除"
        }
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.admin ?: throw IllegalStateException("未连接")
        withContext(Dispatchers.IO) { admin.deleteExchange(name) }
        ctx.cache?.let { cache ->
            cache.getAs<List<RabbitMqExchangeSnapshot>>(KEY_EXCHANGES).orEmpty()
                .filter { it.name != name }
                .let { cache.put(KEY_EXCHANGES, it, ttlMs = objectsTtlMs(ctx)) }
            cache.getAs<List<RabbitMqBindingSnapshot>>(KEY_BINDINGS).orEmpty()
                .filter { it.source != name }
                .let { cache.put(KEY_BINDINGS, it, ttlMs = objectsTtlMs(ctx)) }
            // queues / queueStatus 不受交换机删除影响（队列对象本身还在）。
        }
        context.emit(
            name = RabbitMqEvents.OBJECTS_SCANNED,
            payload = mapOf("connectionId" to connectionId),
        )
        rabbitMqLog.info("exchange deleted: connection=$connectionId name=$name")
    }

    /**
     * 指定交换机的绑定（实时查询，不走缓存 —— 属性对话框要新鲜数据，与
     * getGroupDetail 同一哲学）。
     */
    suspend fun exchangeBindings(connectionId: String, name: String): List<RabbitMqBindingSnapshot> {
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.admin ?: throw IllegalStateException("未连接")
        return withContext(Dispatchers.IO) {
            admin.exchangeBindings(name).map {
                RabbitMqBindingSnapshot(
                    source = it.source,
                    destination = it.destination,
                    destinationType = it.destinationType,
                    routingKey = it.routingKey,
                    arguments = it.arguments,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 连接处于 Connected 的上下文；否则 null（定时任务下一轮由状态守卫跳过）。 */
    private fun connectedContext(connectionId: String): RabbitMqConnectionContext? =
        RabbitMqModule.connectionOrNull(connectionId)
            ?.takeIf { it.status == ConnectionStatus.Connected }

    private fun objectsTtlMs(ctx: RabbitMqConnectionContext): Long =
        ctx.config.objectScanIntervalMinutes * 60_000L * OBJECTS_TTL_FACTOR.toLong()

    private fun statusTtlMs(ctx: RabbitMqConnectionContext): Long =
        ctx.config.statusScanIntervalSeconds * 1_000L * STATUS_TTL_FACTOR.toLong()

    private fun cacheOf(connectionId: String): com.mqclient.core.cache.CacheManager? =
        RabbitMqModule.connectionOrNull(connectionId)?.cache

    /** 整轮扫描失败即这条连接大概率已断：上下文与核心状态一起转 FAILED（UI 显示「连接失败」）。 */
    private fun markFailed(connectionId: String, message: String) {
        RabbitMqModule.connectionOrNull(connectionId)?.let { ctx ->
            if (ctx.status == ConnectionStatus.Connected) {
                ctx.lastError = message
                ctx.status = ConnectionStatus.Failed
                ConnectionStore.reportStatus(connectionId, ConnectionStatus.Failed, context.moduleId)
                rabbitMqLog.warn("connection marked FAILED after scan error: connection=$connectionId")
            }
        }
    }

    /** 给日志 / 事件的失败原因：RabbitMqAdminClient 已产出人类可读文案，直接取 message。 */
    private fun errorMessage(error: Throwable): String =
        error.message
            ?: error::class.simpleName
            ?: "unknown"
}
