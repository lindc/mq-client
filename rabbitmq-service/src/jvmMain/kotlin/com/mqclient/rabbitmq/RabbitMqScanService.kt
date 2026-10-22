package com.mqclient.rabbitmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.concurrent.KeyedMutex
import com.mqclient.core.db.CommandAudit
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * RabbitMQ 对象 / 状态扫描：定时快扫 / 慢扫 + 缓存写入 + 事件通知，与 kafka 的
 * KafkaScanService 同一套模式（write-through 缓存、事件驱动 UI、失败分级）。
 *
 *  - **对象慢扫** [scanObjects]（默认 5min）：exchanges / queues（静态属性）/
 *    bindings / nodes 四类查询（前三类**并发**），全量替换写缓存 —— 全量替换的语义
 *    即「顺带清理已删除的对象」。任一类失败整轮失败（管理 API 是同一个端口的同一套
 *    权限，单类失败大概率意味着连接已断或权限缺口）。
 *  - **状态快扫** [scanStatus]（默认 10s）：`GET /api/queues` 的计数字段全量替换
 *    进 `queueStatus`，发事件通知 UI 刷新队列卡片。跑通 → reportStatus(Connected)
 *    保活（与 Kafka 的 lag 心跳同款）。
 *
 * 缓存：该连接专属的 [com.mqclient.core.cache.CacheManager]（挂
 * [RabbitMqConnectionContext.cache]，连接成功创建、断开清空）。key 约定：
 * `exchanges` / `queues` / `bindings` / `queueStatus` / `nodes`。读口 [cachedQueues] 等
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

        /** 缓存 key：集群节点列表（慢扫，概览页节点区块）。 */
        const val KEY_NODES = "nodes"

        /** 慢扫数据的 TTL 倍率：慢扫间隔 × 1.5 —— 失联后数据别永远烂在缓存里的兜底。 */
        const val OBJECTS_TTL_FACTOR = 1.5f

        /** 快扫数据的 TTL 倍率：快扫间隔 × 2。 */
        const val STATUS_TTL_FACTOR = 2f
    }

    /** 按连接分组的扫描锁：同一条连接串行（防重叠扫描互相覆盖结果），不同连接并发。 */
    private val scanLocks = KeyedMutex()

    // ---------------------------------------------------------------- 对象慢扫

    /**
     * 定时任务入口（`rabbitmq.<connectionId>.scan.objects`）：一轮并发扫齐 exchanges /
     * queues / bindings 三类并全量替换。任一失败整轮失败（见类 KDoc 的取舍）。
     */
    suspend fun scanObjects(connectionId: String) = scanLocks.withLock(connectionId) {
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

            val nodeDtos = withContext(Dispatchers.IO) { admin.nodes() }

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
                        propertiesKey = it.propertiesKey,
                    )
                }
                .sortedWith(compareBy({ it.source }, { it.destination }, { it.routingKey }))
            val nodeSnapshots = nodeDtos
                .map {
                    RabbitMqNodeSnapshot(
                        name = it.name,
                        type = it.type,
                        running = it.running,
                        uptime = it.uptime,
                        memUsed = it.memUsed,
                        memLimit = it.memLimit,
                        memAlarm = it.memAlarm,
                        diskFreeAlarm = it.diskFreeAlarm,
                    )
                }
                .sortedBy { it.name }

            val cache = ctx.cache
            cache?.put(KEY_EXCHANGES, exchangeSnapshots, ttlMs = objectsTtl)
            cache?.put(KEY_QUEUES, queueSnapshots, ttlMs = objectsTtl)
            cache?.put(KEY_BINDINGS, bindingSnapshots, ttlMs = objectsTtl)
            cache?.put(KEY_NODES, nodeSnapshots, ttlMs = objectsTtl)

            context.emit(
                name = RabbitMqEvents.OBJECTS_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "exchangeCount" to exchangeSnapshots.size,
                    "queueCount" to queueSnapshots.size,
                    "bindingCount" to bindingSnapshots.size,
                    "nodeCount" to nodeSnapshots.size,
                ),
            )
            rabbitMqLog.info(
                "objects scanned: connection=$connectionId exchanges=${exchangeSnapshots.size} " +
                    "queues=${queueSnapshots.size} bindings=${bindingSnapshots.size} " +
                    "nodes=${nodeSnapshots.size}",
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
    suspend fun scanStatus(connectionId: String) = scanLocks.withLock(connectionId) {
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

    /** 集群节点列表（慢扫快照）；缓存未就绪 / 过期返回空表。 */
    fun cachedNodes(connectionId: String): List<RabbitMqNodeSnapshot> =
        cacheOf(connectionId)?.getAs(KEY_NODES) ?: emptyList()

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
        arguments: Map<String, String> = emptyMap(),
    ) {
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.admin ?: throw IllegalStateException("未连接")
        val flags = listOfNotNull(
            "type=$type",
            "durable".takeIf { durable },
            "autoDelete".takeIf { autoDelete },
            "args=" + arguments.keys.takeIf { it.isNotEmpty() }?.joinToString("|"),
        ).joinToString(", ")
        audited(connectionId, "CREATE EXCHANGE $name" + flags.takeIf { it.isNotEmpty() }?.let { " [$it]" }) {
            // 值类型化（与 createQueue 同口径）：整数 → Long、true/false → Boolean、其余字符串。
            val args = buildJsonObject {
                arguments.filter { it.key.isNotBlank() }.forEach { (key, value) ->
                    val t = value.trim()
                    put(
                        key,
                        when {
                            t.toLongOrNull() != null -> JsonPrimitive(t.toLong())
                            t.equals("true", ignoreCase = true) -> JsonPrimitive(true)
                            t.equals("false", ignoreCase = true) -> JsonPrimitive(false)
                            else -> JsonPrimitive(t)
                        },
                    )
                }
            }
            withContext(Dispatchers.IO) { admin.createExchange(name, type, durable, autoDelete, args) }
        }
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
        audited(connectionId, "DELETE EXCHANGE $name") {
            withContext(Dispatchers.IO) { admin.deleteExchange(name) }
        }
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
                    propertiesKey = it.propertiesKey,
                )
            }
        }
    }

    /**
     * 添加绑定（实时操作，不改缓存）：POST bindings 端点；成功后由调用方重载
     * 实时列表并触发 scanNow 同步慢扫缓存。
     */
    suspend fun createBinding(
        connectionId: String,
        exchange: String,
        destination: String,
        destinationType: String,
        routingKey: String,
        arguments: Map<String, String> = emptyMap(),
    ) {
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.admin ?: throw IllegalStateException("未连接")
        audited(connectionId, "CREATE BINDING $exchange -> $destination" + routingKey.takeIf { it.isNotBlank() }?.let { " [rk=$it]" }) {
            withContext(Dispatchers.IO) { admin.createBinding(exchange, destination, destinationType, routingKey, arguments) }
        }
        rabbitMqLog.info(
            "binding created: connection=$connectionId exchange=$exchange destination=$destination " +
                "type=$destinationType routingKey=$routingKey",
        )
    }

    /**
     * 删除绑定（实时操作，不改缓存）：DELETE bindings 端点（propertiesKey 随列表
     * 下发）；成功后由调用方重载实时列表并触发 scanNow 同步慢扫缓存。
     */
    suspend fun deleteBinding(
        connectionId: String,
        exchange: String,
        destination: String,
        destinationType: String,
        propertiesKey: String,
    ) {
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.admin ?: throw IllegalStateException("未连接")
        audited(connectionId, "DELETE BINDING $exchange -> $destination" + propertiesKey.takeIf { it.isNotBlank() }?.let { " [rk=$it]" }) {
            withContext(Dispatchers.IO) { admin.deleteBinding(exchange, destination, destinationType, propertiesKey) }
        }
        rabbitMqLog.info(
            "binding deleted: connection=$connectionId exchange=$exchange destination=$destination type=$destinationType",
        )
    }

    /**
     * 测试发布（实时操作，开发辅助）：走管理 API publish 端点，返回 routed
     * （是否路由到至少一个队列，false = 已提交但没进任何队列）。headers 键值对进
     * `properties.headers`；与其他写操作同一模式：连接守卫 + Dispatchers.IO。
     *
     * 消息发布**不进写操作审计**（mq_command 表语义是管理操作，且高频批量会膨胀
     * 审计表 —— 与 IBM MQ 的 MqscExecutor 咽喉点口径一致）。
     */
    suspend fun publishTestMessage(
        connectionId: String,
        exchange: String,
        routingKey: String,
        payload: String,
        headers: Map<String, String>,
        payloadEncoding: String = "string",
    ): Boolean {
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.admin ?: throw IllegalStateException("未连接")
        val routed = withContext(Dispatchers.IO) {
            admin.publish(exchange, routingKey, payload, headers, payloadEncoding)
        }
        rabbitMqLog.info(
            "test message published: connection=$connectionId exchange=$exchange " +
                "routingKey=$routingKey routed=$routed",
        )
        return routed
    }

    /**
     * 队列详情（实时查询，不走缓存 —— 属性对话框要新鲜的 arguments / queue_type /
     * 计数，与 exchangeBindings 同一哲学）。
     */
    suspend fun queueDetail(connectionId: String, name: String): RabbitMqQueueDetailSnapshot {
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.admin ?: throw IllegalStateException("未连接")
        return withContext(Dispatchers.IO) { admin.queueDetail(name) }.let { d ->
            RabbitMqQueueDetailSnapshot(
                name = d.name,
                state = d.stateText,
                queueType = d.queueType,
                durable = d.durable,
                autoDelete = d.autoDelete,
                exclusive = d.exclusive,
                arguments = d.arguments.mapValues { (_, v) -> argumentText(v) },
                messages = d.messages,
                messagesReady = d.messagesReady,
                messagesUnacknowledged = d.messagesUnacknowledged,
                consumers = d.consumers,
            )
        }
    }

    /** argument 值投影：标量取原值（content 已去引号），数组 / 对象 toString。 */
    private fun argumentText(value: kotlinx.serialization.json.JsonElement): String =
        when (value) {
            is kotlinx.serialization.json.JsonPrimitive -> value.content
            else -> value.toString()
        }

    /**
     * 队列消费者列表（实时查询，不走缓存 —— 属性对话框「消费者」Tab 要新鲜数据，
     * 与 queueDetail 同一哲学）。队列不存在 / 无权限由 AdminClient 的 checkStatus
     * 转文案，原样上抛给 UI。
     */
    suspend fun queueConsumers(connectionId: String, name: String): List<RabbitMqConsumerSnapshot> {
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.admin ?: throw IllegalStateException("未连接")
        return withContext(Dispatchers.IO) { admin.queueConsumers(name) }.map {
            RabbitMqConsumerSnapshot(
                consumerTag = it.consumerTag,
                peerHost = it.channelDetails?.peerHost,
                peerPort = it.channelDetails?.peerPort,
                ackRequired = it.ackRequired,
            )
        }
    }

    /**
     * 清空队列（实时操作）：DELETE contents 端点（只删 Ready，Unacked 不受影响）。
     * 成功后触发一轮 scanNow，快扫计数缓存几秒内归零（对话框自身也会重载实时详情）。
     */
    suspend fun purgeQueue(connectionId: String, name: String) {
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.admin ?: throw IllegalStateException("未连接")
        audited(connectionId, "PURGE QUEUE $name") {
            withContext(Dispatchers.IO) { admin.purgeQueue(name) }
        }
        rabbitMqLog.info("queue purged: connection=$connectionId queue=$name")
        RabbitMqModule.scanNow(connectionId)
    }

    /**
     * 创建队列（管理端点 PUT）：成功后同步补缓存（queues 插入新快照）→
     * OBJECTS_SCANNED 驱动列表刷新（与 createExchange 同一模式）。state 未知置
     * null，快扫跑过自然补上。[arguments] 是 x-arguments 原始键值对（值为字符串，
     * 这里统一做类型化：纯整数字符串 → Long、"true"/"false" → Boolean、其余按
     * 字符串 —— 与管理端行为一致）。服务端错误（已存在 / 权限不足等）原样抛给调用方。
     */
    suspend fun createQueue(
        connectionId: String,
        name: String,
        durable: Boolean,
        autoDelete: Boolean,
        exclusive: Boolean,
        arguments: Map<String, String>,
    ) {
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.admin ?: throw IllegalStateException("未连接")
        val args = buildJsonObject {
            arguments
                .filter { it.key.isNotBlank() }
                .forEach { (key, value) ->
                    val t = value.trim()
                    put(
                        key.trim(),
                        when {
                            t == "true" -> JsonPrimitive(true)
                            t == "false" -> JsonPrimitive(false)
                            else -> t.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(t)
                        },
                    )
                }
        }
        val flags = listOfNotNull(
            "durable".takeIf { durable },
            "autoDelete".takeIf { autoDelete },
            "exclusive".takeIf { exclusive },
        ).joinToString(", ")
        audited(connectionId, "CREATE QUEUE $name" + flags.takeIf { it.isNotEmpty() }?.let { " [$it]" }) {
            withContext(Dispatchers.IO) { admin.createQueue(name, durable, autoDelete, exclusive, args) }
        }
        ctx.cache?.let { cache ->
            val current = cache.getAs<List<RabbitMqQueueSnapshot>>(KEY_QUEUES).orEmpty()
                .filter { it.name != name }
            cache.put(
                KEY_QUEUES,
                (current + RabbitMqQueueSnapshot(name, state = null, durable, autoDelete, exclusive))
                    .sortedBy { it.name },
                ttlMs = objectsTtlMs(ctx),
            )
        }
        context.emit(
            name = RabbitMqEvents.OBJECTS_SCANNED,
            payload = mapOf("connectionId" to connectionId),
        )
        rabbitMqLog.info("queue created: connection=$connectionId queue=$name")
    }

    /**
     * 删除队列（实时操作）：成功后同步剪缓存（queues / queueStatus）→ OBJECTS_SCANNED
     * 驱动列表与拓扑刷新（与 deleteExchange 同一模式）。有消费者连接时服务端 400，
     * reason 原样抛给调用方。
     */
    suspend fun deleteQueue(connectionId: String, name: String) {
        val ctx = connectedContext(connectionId) ?: throw IllegalStateException("未连接")
        val admin = ctx.admin ?: throw IllegalStateException("未连接")
        audited(connectionId, "DELETE QUEUE $name") {
            withContext(Dispatchers.IO) { admin.deleteQueue(name) }
        }
        ctx.cache?.let { cache ->
            cache.getAs<List<RabbitMqQueueSnapshot>>(KEY_QUEUES).orEmpty()
                .filter { it.name != name }
                .let { cache.put(KEY_QUEUES, it, ttlMs = objectsTtlMs(ctx)) }
            cache.getAs<Map<String, RabbitMqQueueStatusSnapshot>>(KEY_QUEUE_STATUS).orEmpty()
                .filterKeys { it != name }
                .let { cache.put(KEY_QUEUE_STATUS, it, ttlMs = statusTtlMs(ctx)) }
        }
        context.emit(
            name = RabbitMqEvents.OBJECTS_SCANNED,
            payload = mapOf("connectionId" to connectionId),
        )
        rabbitMqLog.info("queue deleted: connection=$connectionId queue=$name")
    }

    // ---------------------------------------------------------------- 内部

    /** 连接处于 Connected 的上下文；否则 null（定时任务下一轮由状态守卫跳过）。 */
    private fun connectedContext(connectionId: String): RabbitMqConnectionContext? =
        RabbitMqModule.connectionOrNull(connectionId)
            ?.takeIf { it.status == ConnectionStatus.Connected }

    /**
     * 写操作审计包裹：操作前计时，成功 / 失败各发一条 core.mq.command.executed
     * 事件（CommandAudit 内部 runCatching，审计失败不影响本操作）。[command] 是
     * 拼装好的伪命令（CREATE/DELETE/PURGE QUEUE|EXCHANGE、BINDING 等，全大写操作
     * 词打头）；[resultNote] 成功后追加结果注记。消息发布（publishTestMessage /
     * publishDirect）不是管理操作，不经此包裹。
     */
    private suspend fun <T> audited(
        connectionId: String,
        command: String,
        resultNote: ((T) -> String?)? = null,
        block: suspend () -> T,
    ): T {
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            CommandAudit.emit(
                connectionId = connectionId,
                command = command + (resultNote?.invoke(result)?.let { note -> " $note" } ?: ""),
                status = "SUCCESS",
                response = null,
                requestTime = start,
                responseTime = System.currentTimeMillis(),
            )
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CommandAudit.emit(
                connectionId = connectionId,
                command = command,
                status = "FAILED",
                response = CommandAudit.describeError(e),
                requestTime = start,
                responseTime = System.currentTimeMillis(),
            )
            throw e
        }
    }

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
