package com.mqclient.rocketmq

import com.mqclient.core.ConnectionStore
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getString
import com.mqclient.core.log.Log
import com.mqclient.core.spi.MODULE_ID_KEY
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.core.spi.onEvent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.jvm.Synchronized

/**
 * 本模块的模块名 —— **模块内唯一定义处**：`ServiceAdapter.id`、任务 ID 前缀、
 * 日志 tag 全部由它派生，不再各写各的字面量。
 */
internal const val MODULE_ID = "rocketmq"

/** 本模块的日志句柄：tag 与 [MODULE_ID] 同源，调用点不需要再写模块名。 */
internal val rocketMqLog = Log.withTag(MODULE_ID)

/** 测试连接的总超时（与 kafka 同口径）。 */
internal const val TEST_TIMEOUT_MS = 30_000L

/** 扫描任务名：快扫 / 慢扫（与 kafka 同构）。 */
internal const val JOB_SCAN_OBJECTS = "scan.objects"
internal const val JOB_SCAN_LAG = "scan.lag"

/** 慢扫起步的延迟：把首拍让给快扫心跳。 */
internal const val OBJECTS_FIRST_DELAY_MS = 5_000L

/**
 * RocketMQ 模块入口：SPI 生命周期与显式动作事件的中枢，与 KafkaModule 同构。
 */
object RocketMqModule {

    private var moduleContext: ServiceContext? = null
    private var service: RocketMqScanService? = null
    private var manager: RocketMqConnectionManager? = null
    private var messageService: RocketMqMessageService? = null
    private var subscriptions: List<Job> = emptyList()

    /**
     * 每条连接最近一轮对象慢扫**没取到**的类型（key 不存在 = 上一轮完整）。
     *
     * 为什么需要状态而不是只靠事件：事件是瞬时的，用户切到别的 Tab 再切回来，
     * 订阅者已经重建，`OBJECTS_SCANNED` 早就过去了 —— 只靠事件的话提示条会消失，
     * 而数据仍然是旧的。存成 StateFlow 后，提示与缓存事实同寿命，直到下一轮真正
     * 扫全或连接断开才消失。（与 RabbitMQ / Kafka 的 scanFailuresFlow 同一套设计。）
     */
    private val scanFailures = MutableStateFlow<Map<String, Set<RocketMqScanKind>>>(emptyMap())

    /** 只读视图：UI 的 partial 提示条数据源。 */
    val scanFailuresFlow: StateFlow<Map<String, Set<RocketMqScanKind>>> = scanFailures

    /** 记录一轮慢扫的失败类型；空集合表示扫全（顺便摘掉该连接的条目）。 */
    internal fun reportScanFailures(connectionId: String, kinds: Set<RocketMqScanKind>) {
        scanFailures.update { current ->
            if (kinds.isEmpty()) current - connectionId else current + (connectionId to kinds)
        }
    }

    /** 断开 / 删除连接时摘除（旧连接的结果不能诈尸到重连后）。 */
    internal fun clearScanFailures(connectionId: String) {
        scanFailures.update { current -> current - connectionId }
    }

    /** 幂等：重复调用直接返回，不会重复注册订阅。 */
    @Synchronized
    fun initialize(context: ServiceContext) {
        if (manager != null) return

        // 客户端日志落点必须早于任何 RocketMQ 类被触碰（shaded logback 在首个日志类
        // 加载时定格配置）—— 模块启动咽喉点的第一件事，先于 service / manager 创建。
        RocketMqClientLogConfigurator.init()

        val createdService = RocketMqScanService(context)
        val created = RocketMqConnectionManager(context, createdService)
        service = createdService
        messageService = RocketMqMessageService()

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
            // 连接快照（mq_connection 表，供 operation_history 关联）：见
            // RocketMqConnectionSnapshotEmitter。编辑连接时 core 换发新 id（本订阅
            // 收到的 added 即新快照），不另听 updated。
            context.onEvent(EventNames.CONNECTION_ADDED) { event ->
                if (isOwnConnection(event, context)) {
                    RocketMqConnectionSnapshotEmitter.onConnectionSaved(event, context)
                }
            },
            context.onEvent(EventNames.CONNECTION_DELETED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                if (!isOwnConnection(event, context)) return@onEvent
                created.remove(connectionId)
            },
            // ---- 显式动作请求（宿主 app-ui 的右键菜单，事件约定见 ConnectionActions）----
            context.onEvent(RocketMqEvents.CONNECT_REQUESTED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                created.connect(connectionId)
            },
            context.onEvent(RocketMqEvents.DISCONNECT_REQUESTED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                created.disconnectNow(connectionId, reason = "menu request")
            },
            context.onEvent(RocketMqEvents.TEST_REQUESTED) { event ->
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
        rocketMqLog.info(
            "module initialized (moduleId=" + context.moduleId + ", contexts=" +
                created.allContexts().size + ", all disconnected)",
        )
    }

    /** UI 的显式连接入口（连接按钮等）：结果经状态上报与事件通知，不阻塞点击。 */
    fun connectAsync(connectionId: String) {
        val connectionManager = manager ?: return
        moduleContext?.coroutineScope?.launch { connectionManager.connect(connectionId) }
    }

    /** UI 的断开入口（fire-and-forget）。 */
    fun disconnectAsync(connectionId: String) {
        manager?.disconnectAsync(connectionId)
    }

    /** 扫描服务（缓存只读口 / 手动扫描）；模块未初始化时为 null。 */
    fun serviceOrNull(): RocketMqScanService? = service

    /** 消息读写服务（浏览 / 发送）；模块未初始化时为 null（同 KafkaModule.messageServiceOrNull）。 */
    fun messageServiceOrNull(): RocketMqMessageService? = messageService

    /**
     * 手动扫描入口（UI 刷新按钮）：在模块 scope 里跑一轮**对象慢扫**（按连接的 scanLocks
     * 串行）；扫完经 OBJECTS_SCANNED 事件驱动 UI 刷新。
     *
     * 协程按连接登记在 [RocketMqScanService] 里，断开连接时由连接管理器取消 —— 否则它会
     * 拿着已 shutdown 的 AdminExt 继续跑、并占着该连接的扫描锁。
     */
    fun scanNow(connectionId: String) {
        service?.launchManualScan(connectionId)
    }

    /**
     * 「测试连接」入口（连接表单的「完成」用）：按**表单草稿**的配置发起一次临时
     * 连接（cluster info 验证，用完即关），失败抛异常。
     */
    suspend fun testConnection(config: RocketMqConfig) {
        val connectionManager = manager
            ?: throw IllegalStateException("RocketMQ 模块未初始化，无法测试连接")
        connectionManager.testConnection(config)
    }

    /** 一条连接的运行时上下文；未登记时为 null。 */
    fun connectionOrNull(connectionId: String): RocketMqConnectionContext? =
        manager?.contextOrNull(connectionId)

    /** 模块销毁：先断开全部连接（关句柄），再取消订阅，最后摘掉引用。 */
    @Synchronized
    fun shutdown() {
        val subscriptionsToCancel = subscriptions
        val managerToStop = manager
        subscriptions = emptyList()
        manager = null
        try {
            // 模块销毁是同步出口（ModuleRegistry.disposeServices 不挂起）：
            // runBlocking 等全部连接断完再返回 —— 退出路径不能留下没关的句柄。
            managerToStop?.let { connectionManager -> runBlocking { connectionManager.shutdownAll() } }
        } finally {
            subscriptionsToCancel.forEach(Job::cancel)
            service = null
            messageService = null
            moduleContext = null
        }
    }

    /** 「测试连接」请求的处理：配置快照临时连接，结果发 TEST_COMPLETED（成功失败都发）。 */
    private suspend fun handleTestRequested(
        event: com.mqclient.core.event.AppEvent,
        connectionManager: RocketMqConnectionManager,
    ) {
        val connectionId = event.getString("connectionId") ?: return
        val config = connectionManager.contextOrNull(connectionId)?.config
        if (config == null) {
            moduleContext?.emit(
                name = RocketMqEvents.TEST_COMPLETED,
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
            name = RocketMqEvents.TEST_COMPLETED,
            payload = mapOf(
                "connectionId" to connectionId,
                "success" to outcome.isSuccess,
                "message" to (outcome.exceptionOrNull()?.let { it.message ?: it::class.simpleName } ?: ""),
                "durationMs" to (System.currentTimeMillis() - startedAt),
            ),
        )
        rocketMqLog.info(
            "test completed: connection=$connectionId success=" + outcome.isSuccess +
                " duration=" + (System.currentTimeMillis() - startedAt) + "ms",
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
