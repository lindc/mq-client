package com.mqclient.rabbitmq

import com.mqclient.core.concurrent.KeyedJobRegistry
import com.mqclient.core.concurrent.KeyedMutex
import com.mqclient.core.cache.allKindsFailed
import com.mqclient.core.cache.putIfScanned
import com.mqclient.core.db.CommandAudit
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import java.util.concurrent.ConcurrentHashMap
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
 *    bindings / nodes 四类查询**并发**，每类**独立兜底**并全量替换自己的缓存键
 *    —— 全量替换的语义即「顺带清理已删除的对象」。
 *
 *    四类为什么不能共用一条成败线：管理 API 的四类端点权限要求**不同**。最典型的是
 *    `GET /api/nodes` 要 `monitor` / `administrator` tag，而只带 `management` tag 的
 *    账号（标准的只读账号）能读 exchanges / queues / bindings，读 nodes 会 403。
 *    曾经的写法是任一类失败就整轮失败 → 一个完全可用的只读账号被标成
 *    `ConnectionStatus.Failed`，界面全红、点重连也没用。现在单类失败只跳过该类并
 *    保留上一轮缓存；真正判连接已死的是**连续失败阈值**（见下）。
 *  - **连接状态的判定不再由单次结果决定**：扫描失败只记一笔，连续
 *    [SCAN_FAILURE_THRESHOLD] 轮都失败才把连接置 `Failed`；任一成功即计数归零并把
 *    误判的 `Failed` 恢复成 `Connected`。长连接池经代理时偶发的
 *    `header parser received no bytes` 因此不会再造成「明明连着却报未连接」。
 *  - 请求入口（[RabbitMqScanService.queueDetail] 等）不再拿 `status` 当否决票：
 *    只要管理 API 客户端还在就直接发请求，真断了由客户端招供真实原因。
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

/**
 * 对象慢扫的四类查询。
 *
 * 彼此的权限要求并不相同（`/api/nodes` 要 `monitor` / `administrator` tag，其余三类
 * 要 `management` tag），所以失败必须**按类记**、按类兑现到缓存与连接状态上。
 *
 * 公开（不再是 internal）：UI 的「部分数据未刷新」提示条要按类型拼文案 ——
 * 枚举本身不含实现，暴露它比在 UI 里用字符串标签匹配更安全。
 */
enum class RabbitMqScanType(val label: String) {
    EXCHANGES("exchanges"),
    QUEUES("queues"),
    BINDINGS("bindings"),
    NODES("nodes"),
}

/**
 * 四类全失败才算连接断了（见 [allKindsFailed] 的说明）。
 *
 * 四类全失败是一个强信号（四类都读不通），但它仍然走**连续失败阈值**：
 * 慢扫 5 分钟一轮，三次才判死太慢，而真正的断线 10 秒一次的状态快扫会在
 * 30 秒内先报上来 —— 所以慢扫全失败只是佐证，不需要更激进的规则。
 *
 * 单类失败不改连接状态：四类端点的权限要求不同（`/api/nodes` 要 `monitor` tag），
 * 只读账号读不到节点是**正常现象**，不是连接故障。
 */
internal fun isConnectionBroken(failedTypes: Collection<RabbitMqScanType>): Boolean =
    allKindsFailed(failedTypes, RabbitMqScanType.entries)

/** 四元组（Kotlin 只有 Triple；四类并发结果 + 折叠成一个）。 */
private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

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

    /**
     * 手动刷新（UI 的「重新扫描」按钮）的协程：同一连接只留一条。
     *
     * 定时任务的协程归 `JobScheduler` 管，这里只收「用户点出来的那一次」。断开 / 删除
     * 连接时必须经 [cancelScan] 收掉 —— 否则它会拿着已经 `close()` 的 HttpClient
     * 继续跑，而且一直占着 [scanLocks] 的 key，重连后的第一次刷新要排在它后面等
     * （表现是「点刷新没反应」）。
     */
    private val manualScans = KeyedJobRegistry()

    /**
     * 启动一次手动对象扫描：与定时慢扫共用 [scanLocks]，所以不会与正在跑的那一轮重叠；
     * 同一连接重复点击顶掉上一轮（与 JobScheduler 的「同 ID 重注册」同口径）。
     */
    fun launchManualScan(connectionId: String) {
        manualScans.launch(context.coroutineScope, connectionId) { scanObjects(connectionId) }
    }

    /**
     * 取消该连接上正在进行的手动扫描（断开 / 删除连接时由连接管理器调用）。
     * 只 cancel 不 join：阻塞中的 HTTP 调用不会被立刻打断，断开路径不能被一次慢查询拖住。
     */
    fun cancelScan(connectionId: String) {
        manualScans.cancel(connectionId)
    }

    // ---------------------------------------------------------------- 对象慢扫

    /**
     * 定时任务入口（`rabbitmq.<connectionId>.scan.objects`）：一轮并发扫齐 exchanges /
     * queues / bindings / nodes 四类，**各自**全量替换自己的缓存键。
     *
     * 失败语义（与之前「任一失败整轮失败」相反，原因见类 KDoc）：
     *  - 单类失败 → 跳过该类（**不写**它的缓存键，保留上一轮结果），发
     *    `OBJECTS_SCANNED` 带 `partial=true`；连接状态不动。
     *  - 四类全失败 → 管理 API 一条都读不通，判定连接真的断了：发
     *    `OBJECTS_SCAN_FAILED` 并 `markFailed`，让界面给「重连」。
     */
    suspend fun scanObjects(connectionId: String) = scanLocks.withLock(connectionId) {
        val (ctx, admin) = scanTargetOrNull(connectionId) ?: return@withLock
        val objectsTtl = objectsTtlMs(ctx)
        // 四路 async 并发写，用线程安全容器（每类只写自己的 key）。
        val failures = ConcurrentHashMap<RabbitMqScanType, String>()
        try {
            val (exchangeDtos, queueDtos, bindingDtos, nodeDtos) = withContext(Dispatchers.IO) {
                coroutineScope {
                    val exchanges = async {
                        scanTypeOr(failures, RabbitMqScanType.EXCHANGES, emptyList()) { admin.exchanges() }
                    }
                    val queues = async {
                        scanTypeOr(failures, RabbitMqScanType.QUEUES, emptyList()) { admin.queues() }
                    }
                    val bindings = async {
                        scanTypeOr(failures, RabbitMqScanType.BINDINGS, emptyList()) { admin.bindings() }
                    }
                    val nodes = async {
                        scanTypeOr(failures, RabbitMqScanType.NODES, emptyList()) { admin.nodes() }
                    }
                    Quad(exchanges.await(), queues.await(), bindings.await(), nodes.await())
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
            // 只为**本轮真的扫到了**的类型写缓存。
            cache.putIfScanned(KEY_EXCHANGES, RabbitMqScanType.EXCHANGES, failures, exchangeSnapshots, objectsTtl)
            cache.putIfScanned(KEY_QUEUES, RabbitMqScanType.QUEUES, failures, queueSnapshots, objectsTtl)
            cache.putIfScanned(KEY_BINDINGS, RabbitMqScanType.BINDINGS, failures, bindingSnapshots, objectsTtl)
            cache.putIfScanned(KEY_NODES, RabbitMqScanType.NODES, failures, nodeSnapshots, objectsTtl)

            // 把「哪几类没取到」交给模块存起来：UI 的提示条要能在页面切来切去后
            // 仍然知道「这份数据是残的」，而事件是瞬时的。
            RabbitMqModule.reportScanFailures(connectionId, failures.keys.toSet())

            // 四类全失败：管理 API 一条都读不通，连接大概率真的断了（或者凭据失效）。
            // 只有这种情况才动连接状态 —— 单类（尤其是只为 `monitor` tag 才有的 nodes）
            // 失败是权限缺口，不是连接坏了。
            if (isConnectionBroken(failures.keys)) {
                val message = failures.values.firstOrNull() ?: "管理 API 全部端点均不可达"
                rabbitMqLog.error(
                    "objects scan failed (all endpoints): connection=$connectionId " +
                        "errors=" + failures.entries.joinToString(", ") { "${it.key.label}=${it.value}" },
                )
                context.emit(
                    name = RabbitMqEvents.OBJECTS_SCAN_FAILED,
                    payload = mapOf("connectionId" to connectionId, "message" to message),
                )
                markFailed(connectionId, message)
                return@withLock
            }

            context.emit(
                name = RabbitMqEvents.OBJECTS_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    // 部分结果：UI / 日志据此区分「扫全了」和「缺了几类」。
                    "partial" to failures.isNotEmpty(),
                    "failedKinds" to failures.keys.map { it.label },
                    "exchangeCount" to exchangeSnapshots.size,
                    "queueCount" to queueSnapshots.size,
                    "bindingCount" to bindingSnapshots.size,
                    "nodeCount" to nodeSnapshots.size,
                ),
            )
            if (failures.isEmpty()) {
                rabbitMqLog.info(
                    "objects scanned: connection=$connectionId exchanges=${exchangeSnapshots.size} " +
                        "queues=${queueSnapshots.size} bindings=${bindingSnapshots.size} " +
                        "nodes=${nodeSnapshots.size}",
                )
            } else {
                rabbitMqLog.warn(
                    "objects scanned (partial): connection=$connectionId exchanges=${exchangeSnapshots.size} " +
                        "queues=${queueSnapshots.size} bindings=${bindingSnapshots.size} " +
                        "nodes=${nodeSnapshots.size} failed=${failures.keys.map { it.label }}",
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // 四类都已各自兜住，走到这里的是「没预期到的」错误（编码 / 内存 / 编程错误）：
            // 不冒充连接故障，只报错并记 fail，不改连接状态。
            val message = errorMessage(error)
            rabbitMqLog.error("objects scan crashed: connection=$connectionId error=$message")
            context.emit(
                name = RabbitMqEvents.OBJECTS_SCAN_FAILED,
                payload = mapOf("connectionId" to connectionId, "message" to message),
            )
        }
    }

    // ---------------------------------------------------------------- 状态快扫

    /**
     * 定时任务入口（`rabbitmq.<connectionId>.scan.status`）：`GET /api/queues` 的
     * 计数字段全量替换进 `queueStatus`。跑通即保活（reportStatus Connected，核心侧
     * 状态没变时直接忽略）。
     */
    suspend fun scanStatus(connectionId: String) = scanLocks.withLock(connectionId) {
        val (ctx, admin) = scanTargetOrNull(connectionId) ?: return@withLock
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
            // 心跳跑通了即这条连接是活的：计数归零，若之前被误判 Failed 则自动恢复。
            RabbitMqModule.reportScanSuccess(connectionId)
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
        val (ctx, admin) = targetOrThrow(connectionId)
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
        // 原子读-改-写：慢扫 / 状态快扫在这两步之间可能整表替换过，先读后写会把刚扫到的
        // 其它交换机（含另一次创建）整段抹掉。
        ctx.cache?.let { cache ->
            cache.updateAs<List<RabbitMqExchangeSnapshot>>(KEY_EXCHANGES, objectsTtlMs(ctx)) { current ->
                (current.orEmpty().filter { it.name != name } +
                    RabbitMqExchangeSnapshot(name, type, durable, autoDelete, internal = false))
                    .sortedBy { it.name }
            }
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
        val (ctx, admin) = targetOrThrow(connectionId)
        audited(connectionId, "DELETE EXCHANGE $name") {
            withContext(Dispatchers.IO) { admin.deleteExchange(name) }
        }
        ctx.cache?.let { cache ->
            // 原子读-改-写：两处剪除各自一次 CAS，不与并发的慢扫互相覆盖。
            cache.updateIfPresentAs<List<RabbitMqExchangeSnapshot>>(KEY_EXCHANGES, objectsTtlMs(ctx)) { rows ->
                rows.filter { it.name != name }
            }
            cache.updateIfPresentAs<List<RabbitMqBindingSnapshot>>(KEY_BINDINGS, objectsTtlMs(ctx)) { rows ->
                rows.filter { it.source != name }
            }
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
        val (ctx, admin) = targetOrThrow(connectionId)
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
        val (ctx, admin) = targetOrThrow(connectionId)
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
        val (ctx, admin) = targetOrThrow(connectionId)
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
        val (ctx, admin) = targetOrThrow(connectionId)
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
     * 计数 / 消费者明细，与 exchangeBindings 同一哲学）。
     *
     * 消费者明细走详情端点自带的 `consumer_details` 字段，**不另发请求**：管理 API
     * 没有「某队列的消费者」这个端点（只有 `/api/consumers/{vhost}`，还得全量拉回
     * 再按队列名过滤）。一次 GET 就把三个 Tab 的数据全拿到，消费者 Tab 也就没有
     * 自己的加载态可卡。
     */
    suspend fun queueDetail(connectionId: String, name: String): RabbitMqQueueDetailSnapshot {
        val (ctx, admin) = targetOrThrow(connectionId)
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
                consumerDetails = d.consumerDetails.orEmpty().map {
                    RabbitMqConsumerSnapshot(
                        consumerTag = it.consumerTag,
                        peerHost = it.channelDetails?.peerHost,
                        peerPort = it.channelDetails?.peerPort,
                        ackRequired = it.ackRequired,
                    )
                },
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
     * 清空队列（实时操作）：DELETE contents 端点（只删 Ready，Unacked 不受影响）。
     * 成功后触发一轮 scanNow，快扫计数缓存几秒内归零（对话框自身也会重载实时详情）。
     */
    suspend fun purgeQueue(connectionId: String, name: String) {
        val (ctx, admin) = targetOrThrow(connectionId)
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
        val (ctx, admin) = targetOrThrow(connectionId)
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
            // 原子读-改-写：理由同 createExchange（并发扫描会覆盖这次插入）。
            cache.updateAs<List<RabbitMqQueueSnapshot>>(KEY_QUEUES, objectsTtlMs(ctx)) { current ->
                (current.orEmpty().filter { it.name != name } +
                    RabbitMqQueueSnapshot(name, state = null, durable, autoDelete, exclusive))
                    .sortedBy { it.name }
            }
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
        val (ctx, admin) = targetOrThrow(connectionId)
        audited(connectionId, "DELETE QUEUE $name") {
            withContext(Dispatchers.IO) { admin.deleteQueue(name) }
        }
        ctx.cache?.let { cache ->
            // 原子读-改-写：queues 与 queueStatus 各一次 CAS；慢扫的全量替换不再能
            // 「读到旧表 → 写回」把这次删除抹掉。
            cache.updateIfPresentAs<List<RabbitMqQueueSnapshot>>(KEY_QUEUES, objectsTtlMs(ctx)) { rows ->
                rows.filter { it.name != name }
            }
            cache.updateIfPresentAs<Map<String, RabbitMqQueueStatusSnapshot>>(KEY_QUEUE_STATUS, statusTtlMs(ctx)) { rows ->
                rows.filterKeys { it != name }
            }
        }
        context.emit(
            name = RabbitMqEvents.OBJECTS_SCANNED,
            payload = mapOf("connectionId" to connectionId),
        )
        rabbitMqLog.info("queue deleted: connection=$connectionId queue=$name")
    }

    // ---------------------------------------------------------------- 内部

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

    /**
     * 整轮扫描失败 → 交给连接管理器按「连续失败阈值」判定是否置 Failed。
     *
     * 旧写法是这里直接写 `ctx.status = Failed`：单次失败就判死，而扫描入口本身
     * 又要求 `Connected` —— 判死之后心跳再也不跑，于是一条健康连接被永久误判为
     * 「未连接」（真实现场见 mqclient.log：四类对象扫描刚全部成功，5 秒后一次
     * `header parser received no bytes` 就把连接标成 FAILED）。现在状态翻转集中在
     * [RabbitMqConnectionManager.onScanFailed]，单次抖动只计数、不翻状态。
     */
    private fun markFailed(connectionId: String, message: String) {
        RabbitMqModule.reportScanFailure(connectionId, message)
    }

    /**
     * 请求入口的**统一守卫**：只看「有没有管理 API 客户端」，不拿 status 当否决票。
     *
     * 这是本次误判的关键修正。`status` 是**扫描结论**（可能因一次抖动被误标），
     * `admin` 才是**能力事实**（客户端在 = 可以发请求）。用结论去否决能力，就会
     * 出现「明明连着却报未连接」；反过来让请求自己去试，真断了它会抛连接异常，
     * 那时展示的才是真实原因。
     */
    private data class ScanTarget(
        val ctx: RabbitMqConnectionContext,
        val admin: RabbitMqAdminClient,
    )

    /** 有客户端就返回；真的没有则抛带具体原因的异常（未登记 / 连接中 / 失败原因 / 未连）。 */
    private fun targetOrThrow(connectionId: String): ScanTarget {
        val ctx = RabbitMqModule.connectionOrNull(connectionId)
            ?: throw IllegalStateException("连接未登记：该连接已被删除或模块未初始化")
        val admin = ctx.admin ?: throw IllegalStateException(connectionRefusalText(ctx))
        return ScanTarget(ctx, admin)
    }

    /**
     * 定时扫描专用的宽松版：只要客户端还在就返回，`status` 不参与判定。
     *
     * 为什么定时扫描必须忽略 status：只有这样才能**自愈** —— 被判 Failed 之后
     * 心跳继续跑，第一次成功即可恢复成 Connected（见
     * [RabbitMqConnectionManager.onScanSucceeded]）。
     */
    private fun scanTargetOrNull(connectionId: String): ScanTarget? {
        val ctx = RabbitMqModule.connectionOrNull(connectionId) ?: return null
        val admin = ctx.admin ?: return null
        return ScanTarget(ctx, admin)
    }

    /** 给日志 / 事件的失败原因：RabbitMqAdminClient 已产出人类可读文案，直接取 message。 */
    private fun errorMessage(error: Throwable): String =
        error.message
            ?: error::class.simpleName
            ?: "unknown"

    /**
     * 单类查询的兜底：失败 → [fallback] 且把该类登记进 [failures]（带错误文案）。
     *
     * 取消除外：取消不是「这个类失败了」，原样上抛让整轮退出。
     */
    internal suspend fun <T> scanTypeOr(
        failures: MutableMap<RabbitMqScanType, String>,
        type: RabbitMqScanType,
        fallback: T,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        val message = errorMessage(error)
        failures[type] = message
        // WARN 而不是 ERROR：单类失败很可能是权限缺口（预期内的，比如只读账号读 nodes），
        // 真正「整轮不可用」由调用方在四类全失败时升为 ERROR。
        rabbitMqLog.warn("objects scan type skipped: kind=${type.label} error=$message")
        fallback
    }
}
