package com.mqclient.rabbitmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getString
import com.mqclient.core.log.Log
import com.mqclient.core.spi.MODULE_ID_KEY
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.core.spi.onEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.jvm.Synchronized

/**
 * 本模块的模块名 —— **模块内唯一定义处**：`ServiceAdapter.id`、事件名前缀、
 * 日志 tag、定时任务 ID 段全部由它派生，不再各写各的字面量。
 */
internal const val MODULE_ID = "rabbitmq"

/** 本模块的日志句柄：tag 与 [MODULE_ID] 同源，调用点不需要再写模块名。 */
internal val rabbitMqLog = Log.withTag(MODULE_ID)

/** 「测试连接」的兜底超时：overview 验证是阻塞等待，不能无限等。 */
internal const val TEST_TIMEOUT_MS = 30_000L

/** 对象慢扫任务的任务类型段（`rabbitmq.<conn>.scan.objects`）。 */
internal const val JOB_SCAN_OBJECTS = "scan.objects"

/** 状态快扫任务的任务类型段（`rabbitmq.<conn>.scan.status`）。 */
internal const val JOB_SCAN_STATUS = "scan.status"

/** 对象慢扫的起步延迟：先把首拍让给状态快扫（见 RabbitMqConnectionManager.registerScanJobs）。 */
internal const val OBJECTS_FIRST_DELAY_MS = 5_000L

/**
 * RabbitMQ 模块的初始化入口 —— **显式连接模型**（与 kafka / ibm-mq 同构）。
 *
 * 启动时（以及 `connection.added` 时）**只登记 DISCONNECTED 上下文**，不主动连接；
 * 只有用户显式动作（右键菜单的连接 / 测试）才会真正建连。阶段一做三件事：
 *  1. 订阅核心的连接事件：`added` → 登记上下文；`updated` → 换配置快照（已连接则
 *     断开，等用户重连）；`deleted` → 断开并摘除上下文；
 *  2. 订阅宿主的显式动作请求：`rabbitmq.connection.connect / disconnect / test`；
 *  3. 为启动时已存在的连接补登记上下文（同样只是 DISCONNECTED 登记）。
 *
 * 订阅挂在 `context.coroutineScope`（模块自己的 scope）上；[shutdown] 时先断开
 * 全部连接再取消订阅。
 */
object RabbitMqModule {

    private var moduleContext: ServiceContext? = null
    private var service: RabbitMqScanService? = null
    private var manager: RabbitMqConnectionManager? = null

    /**
     * 每条连接最近一轮对象慢扫**没取到**的类型（key 不存在 = 上一轮完整）。
     *
     * 为什么需要这个状态而不是只靠事件：事件是瞬时的，用户切到别的 Tab 再切回来，
     * 订阅者已经重建，`OBJECTS_SCANNED` 早就过去了 —— 只靠事件的话提示条会消失，
     * 而数据仍然是旧的（这正是「界面上看不出东西不对」的那类情况）。存成 StateFlow
     * 后，提示与缓存事实同寿命，直到下一轮真正扫全或连接断开才消失。
     *
     * 对应的事件 payload 仍有 `partial` / `failedKinds`（日志与即时反应用），
     * 两者是同一事实的两个出口，不是两份逻辑。
     */
    private val scanFailures = MutableStateFlow<Map<String, Set<RabbitMqScanType>>>(emptyMap())

    /** 只读视图：UI 的 partial 提示条数据源。 */
    val scanFailuresFlow: StateFlow<Map<String, Set<RabbitMqScanType>>> = scanFailures

    /**
     * 记录一轮慢扫的失败类型；空集合表示扫全（顺便摘掉该连接的条目）。
     */
    internal fun reportScanFailures(connectionId: String, kinds: Set<RabbitMqScanType>) {
        scanFailures.update { current ->
            if (kinds.isEmpty()) current - connectionId else current + (connectionId to kinds)
        }
    }

    /** 断开 / 删除连接时摘除（旧连接的结果不能诈尸到重连后）。 */
    internal fun clearScanFailures(connectionId: String) {
        scanFailures.update { current -> current - connectionId }
    }
    private var subscriptions: List<Job> = emptyList()

    /**
     * 幂等：重复调用直接返回，不会重复注册订阅。
     */
    @Synchronized
    fun initialize(context: ServiceContext) {
        if (manager != null) return

        val createdService = RabbitMqScanService(context)
        val created = RabbitMqConnectionManager(context, createdService)
        service = createdService

        subscriptions = listOf(
            // 跨模块：核心广播连接变化，只处理属于本模块的连接（payload 里 `type`
            // 即 serviceId，见 ConnectionStore.emitChange 的文档）。
            context.onEvent(EventNames.CONNECTION_ADDED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                if (!isOwnConnection(event, context)) return@onEvent
                // 只登记，不连接 —— 显式连接模型的核心。
                ownConnectionById(connectionId)?.let { created.ensureContext(it) }
            },
            context.onEvent(EventNames.CONNECTION_UPDATED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                if (!isOwnConnection(event, context)) return@onEvent
                created.updateContextConfig(ownConnectionById(connectionId) ?: return@onEvent)
            },
            // 连接快照（mq_connection 表，供 operation_history 关联）：payload 组装在本模块
            // （只有它认识 options 里的 managementPort / vhost 键），写库在 app-core 的
            // MqConnectionSnapshotHandler —— 见 RabbitMqConnectionSnapshotEmitter。
            context.onEvent(EventNames.CONNECTION_ADDED) { event ->
                if (isOwnConnection(event, context)) {
                    RabbitMqConnectionSnapshotEmitter.onConnectionSaved(event, context)
                }
            },
            context.onEvent(EventNames.CONNECTION_DELETED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                if (!isOwnConnection(event, context)) return@onEvent
                created.remove(connectionId)
            },
            // ---- 显式动作请求（宿主 app-ui 的右键菜单，事件约定见 ConnectionActions）----
            context.onEvent(RabbitMqEvents.CONNECT_REQUESTED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                created.connect(connectionId)
            },
            context.onEvent(RabbitMqEvents.DISCONNECT_REQUESTED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                created.disconnectNow(connectionId, reason = "menu request")
            },
            context.onEvent(RabbitMqEvents.TEST_REQUESTED) { event ->
                handleTestRequested(event, created)
            },
        )

        // 启动时已经在配置里的连接：补登记 DISCONNECTED 上下文。**不连接** ——
        // 等用户显式动手。
        ConnectionStore.connections.value
            .filter { it.serviceId == context.moduleId }
            .forEach { created.ensureContext(it) }

        manager = created
        moduleContext = context
        rabbitMqLog.info(
            "module initialized (moduleId=${context.moduleId}, " +
                "contexts=${created.allContexts().size}, all disconnected)",
        )
    }

    /**
     * UI 的显式连接入口（连接按钮等）：在模块 scope 里跑
     * [RabbitMqConnectionManager.connect]，结果经状态上报与事件通知，不阻塞点击。
     */
    fun connectAsync(connectionId: String) {
        val connectionManager = manager ?: return
        moduleContext?.coroutineScope?.launch { connectionManager.connect(connectionId) }
    }

    /** UI 的断开入口（fire-and-forget）。 */
    fun disconnectAsync(connectionId: String) {
        manager?.disconnectAsync(connectionId)
    }

    /** 扫描服务（缓存只读口 / 手动扫描）；模块未初始化时为 null。 */
    fun serviceOrNull(): RabbitMqScanService? = service

    /**
     * 手动扫描入口（UI 刷新按钮）：在模块 scope 里跑一轮**对象慢扫**（按连接的 scanLocks 串行，
     * 与定时任务排队不重叠）；扫完经 OBJECTS_SCANNED 事件驱动 UI 刷新缓存数据。
     *
     * 协程按连接登记在 [RabbitMqScanService] 里，断开连接时由连接管理器取消 —— 否则它会
     * 拿着已关闭的 HTTP 客户端继续跑、并占着该连接的扫描锁。
     */
    fun scanNow(connectionId: String) {
        service?.launchManualScan(connectionId)
    }

    /**
     * 仍开着的消息会话（对话框持有）：模块销毁时统一关闭。
     *
     * 光靠对话框的 `onDispose` 不够：应用退出时 `ModuleRegistry.disposeServices()` 先跑
     * （把 `moduleContext` 置空），对话框的 onDispose 随后才到，那时
     * [closeMessageSessionAsync] 已经没有 scope 可用 —— 会话就漏在那里了。
     */
    private val openSessions: MutableSet<RabbitMqQueueSession> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 队列消息会话入口（队列消息对话框）：AMQP 直连当前队列（autoAck = false）。
     * 连接必须处于 Connected；会话生命周期归对话框 —— 关闭时调 [closeMessageSessionAsync]。
     */
    suspend fun openMessageSession(connectionId: String, queueName: String): RabbitMqQueueSession {
        val ctx = connectionOrNull(connectionId)?.takeIf { it.status == ConnectionStatus.Connected }
            ?: throw IllegalStateException("未连接")
        return RabbitMqMessageSession.open(ctx.config, queueName).also { openSessions.add(it) }
    }

    /** 会话关闭的异步兜底（对话框 onDispose 是同步回调，挂起 close 另起协程跑）。 */
    fun closeMessageSessionAsync(session: RabbitMqQueueSession?) {
        if (session == null) return
        openSessions.remove(session)
        moduleContext?.coroutineScope?.launch { runCatching { session.close() } }
    }

    /**
     * 「测试连接」入口（连接表单的「完成」用）：按**表单草稿**的配置发起一次临时
     * 连接（overview 验证，用完即关），失败抛异常。与 `rabbitmq.connection.test`
     * 事件版的差异：事件版只测已登记连接的配置快照，表单需要测的是还没落盘的草稿。
     * 模块未初始化（无头环境 / 测试）抛状态异常，由调用方按测试失败处理。
     */
    suspend fun testConnection(config: RabbitMqConfig) {
        val connectionManager = manager
            ?: throw IllegalStateException("RabbitMQ 模块未初始化，无法测试连接")
        connectionManager.testConnection(config)
    }

    /** 一条连接的运行时上下文；未登记时为 null。 */
    fun connectionOrNull(connectionId: String): RabbitMqConnectionContext? =
        manager?.contextOrNull(connectionId)

    /**
     * 模块销毁：先断开全部连接（关句柄），再取消订阅，最后摘掉引用。顺序反了的话
     * 断开过程中发出的 `rabbitmq.*` 事件会无人处理。
     */
    @Synchronized
    fun shutdown() {
        val subscriptionsToCancel = subscriptions
        val managerToStop = manager
        val sessionsToClose = openSessions.toList()
        openSessions.clear()
        subscriptions = emptyList()
        manager = null
        try {
            // 先关消息会话：它们不挂在连接上下文上（归对话框），[shutdownAll] 收不到。
            sessionsToClose.forEach { session ->
                runBlocking {
                    try {
                        session.close()
                    } catch (error: Throwable) {
                        // 一条关不掉的会话不该拦住后面的会话与连接收尾。
                        rabbitMqLog.warn(
                            "message session close failed: ${error.message ?: error::class.simpleName}",
                        )
                    }
                }
            }
            // 模块销毁是同步出口（ModuleRegistry.disposeServices 不挂起）：
            // runBlocking 等全部连接断完再返回 —— 退出路径不能留下没关的句柄。
            managerToStop?.let { connectionManager -> runBlocking { connectionManager.shutdownAll() } }
        } finally {
            subscriptionsToCancel.forEach(Job::cancel)
            service = null
            moduleContext = null
        }
    }

    /**
     * 「测试连接」请求的处理：取该连接的配置快照发起一次**临时连接**（overview 验证，
     * 用完即关，不影响正式连接的状态），结果发 [RabbitMqEvents.TEST_COMPLETED] ——
     * 成功失败都发，宿主据此弹结果对话框。配置未登记（上下文不存在）按失败上报。
     */
    private suspend fun handleTestRequested(
        event: com.mqclient.core.event.AppEvent,
        connectionManager: RabbitMqConnectionManager,
    ) {
        val connectionId = event.getString("connectionId") ?: return
        val config = connectionManager.contextOrNull(connectionId)?.config
        if (config == null) {
            moduleContext?.emit(
                name = RabbitMqEvents.TEST_COMPLETED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "success" to false,
                    "message" to "连接 '$connectionId' 未登记",
                    "durationMs" to 0L,
                ),
            )
            return
        }
        val startedAt = System.currentTimeMillis()
        // 超时 / 连接失败 → Result.failure；真正的协程取消（模块 shutdown 等）
        // 必须原样上抛，否则取消语义被吞。
        val outcome = try {
            Result.success(withTimeout(TEST_TIMEOUT_MS) { connectionManager.testConnection(config) })
        } catch (e: TimeoutCancellationException) {
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        moduleContext?.emit(
            name = RabbitMqEvents.TEST_COMPLETED,
            payload = mapOf(
                "connectionId" to connectionId,
                "success" to outcome.isSuccess,
                "message" to (outcome.exceptionOrNull()?.let { it.message ?: it::class.simpleName } ?: ""),
                "durationMs" to (System.currentTimeMillis() - startedAt),
            ),
        )
        rabbitMqLog.info(
            "test completed: connection=$connectionId success=${outcome.isSuccess} " +
                "duration=${System.currentTimeMillis() - startedAt}ms",
        )
    }

    /** 事件是否属于本模块的连接（`core.connection.*` 的 payload 里 `type` = serviceId）。 */
    private fun isOwnConnection(
        event: com.mqclient.core.event.AppEvent,
        context: ServiceContext,
    ): Boolean = event.getString(MODULE_ID_KEY) == context.moduleId ||
        event.getString("type") == context.moduleId

    private fun ownConnectionById(connectionId: String) =
        ConnectionStore.connections.value.firstOrNull { it.id == connectionId }
}
