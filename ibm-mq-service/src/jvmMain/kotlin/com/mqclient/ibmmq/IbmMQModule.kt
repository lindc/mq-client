package com.mqclient.ibmmq

import com.mqclient.core.ConnectionStore
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getString
import com.mqclient.core.log.Log
import com.mqclient.core.spi.MODULE_ID_KEY
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.core.spi.onEvent
import com.mqclient.core.spi.onOwnEvents
import com.mqclient.ibmmq.command.MqscExecutor
import com.mqclient.ibmmq.command.MqscHistoryRepository
import com.mqclient.ibmmq.command.MqscPcfSender
import com.mqclient.ibmmq.event.IbmMQEvents
import com.mqclient.ibmmq.pcf.MqObjectType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.jvm.Synchronized

/**
 * 本模块的模块名 —— **模块内唯一定义处**：`ServiceAdapter.id`、任务 ID 前缀、
 * 日志 tag 全部由它派生，不再各写各的字面量。
 */
internal const val MODULE_ID = "ibm-mq"

/** 本模块的日志句柄：tag 与 [MODULE_ID] 同源，调用点不需要再写模块名。 */
internal val ibmMqLog = Log.withTag(MODULE_ID)

/** 任务类型（任务 ID 第三段）：`ibm-mq.<connectionId>.scan.messages`。 */
internal const val JOB_SCAN_MESSAGES = "scan.messages"
internal const val JOB_SCAN_OBJECTS = "scan.objects"

/** 对象列表扫描的起步延迟：先把首拍让给消息扫描（见 IbmMQConnectionManager.registerScanJobs）。 */
internal const val OBJECTS_FIRST_DELAY_MS = 5_000L

/** 「测试连接」的兜底超时：MQ 的 connect 是阻塞 IO（见 IbmMQService.testConnection），不能无限等。 */
internal const val TEST_TIMEOUT_MS = 30_000L

/**
 * IBM MQ 模块的初始化入口 —— **显式连接模型**。
 *
 * 与旧模型的分界：启动时（以及 `connection.added` 时）**只登记 DISCONNECTED 上下文**，
 * 不主动连接、不建 cache、不注册任何定时任务；扫描任务的注册挪到了
 * [IbmMQConnectionManager.connect]（连接成功后按用户配置的间隔注册）。只有用户
 * 显式动作（连接按钮 / 将来的 queue/topic 点击）才会经 [connectAsync] 建立连接。
 *
 * 做四件事：
 *  1. 用**前缀订阅**收自己的事件（`ibm-mq.*`），交给 [IbmMQEventHandler]；
 *  2. 订阅核心的连接事件：`added` → 登记上下文；`updated` → 换配置快照
 *     （已连接则断开，等用户重连）；`deleted` → 断开并摘除上下文与本地数据；
 *  3. 为启动时已存在的连接补登记上下文（同样只是 DISCONNECTED 登记）；
 *  4. 创建 [IbmMQConnectionManager]（连接生命周期）与 [IbmMQService]（业务操作）。
 *
 * 订阅挂在 `context.coroutineScope`（模块自己的 scope）上，返回的 [Job] 由本对象持有；
 * [shutdown] 时先断开全部连接再取消订阅。
 */
object IbmMQModule {

    private var moduleContext: ServiceContext? = null
    private var service: IbmMQService? = null
    private var manager: IbmMQConnectionManager? = null
    private var mqscExecutor: MqscExecutor? = null
    private var subscriptions: List<Job> = emptyList()

    /**
     * 每条连接最近一轮对象慢扫**没取到**的类型（key 不存在 = 上一轮完整）。
     *
     * 为什么需要状态而不是只靠事件：事件是瞬时的，用户切到别的 Tab 再切回来，
     * 订阅者已经重建，`OBJECTS_SCANNED` 早就过去了 —— 只靠事件的话提示条会消失，
     * 而数据仍然是旧的。存成 StateFlow 后，提示与缓存事实同寿命，直到下一轮真正
     * 扫全或连接断开才消失。（与 RabbitMQ / Kafka / RocketMQ 同一套设计。）
     */
    private val scanFailures = MutableStateFlow<Map<String, Set<MqObjectType>>>(emptyMap())

    /** 只读视图：UI 的 partial 提示条数据源。 */
    val scanFailuresFlow: StateFlow<Map<String, Set<MqObjectType>>> = scanFailures

    /** 记录一轮慢扫的失败类型；空集合表示扫全（顺便摘掉该连接的条目）。 */
    internal fun reportScanFailures(connectionId: String, types: Set<MqObjectType>) {
        scanFailures.update { current ->
            if (types.isEmpty()) current - connectionId else current + (connectionId to types)
        }
    }

    /** 断开 / 删除连接时摘除（旧连接的结果不能诈尸到重连后）。 */
    internal fun clearScanFailures(connectionId: String) {
        scanFailures.update { current -> current - connectionId }
    }

    /**
     * 各连接的对象数量（慢扫产物）：connectionId → (对象类型 → 数量)。导航树二级
     * 菜单的数量徽章数据源 —— [IbmMqServiceAdapter.badgeCounts] 从它派生（翻译成
     * 导航项 id）。[IbmMQService.scanObjects] 成功后按类型**覆盖**该连接的条目，
     * 连接删除时摘除。
     */
    private val objectCounts = MutableStateFlow<Map<String, Map<MqObjectType, Int>>>(emptyMap())

    /** 徽章数据源的只读视图：adapter 按连接派生（map + 翻译键），不经快照。 */
    val objectCountsFlow: StateFlow<Map<String, Map<MqObjectType, Int>>> = objectCounts

    /**
     * 只覆盖 [counts] 里**真的指定到的**类型，其余保留上一轮的数字。
     *
     * 刻意不做「全量替换」：扫描可能是部分结果（预算耗尽 / 某类无权限），而
     * [counts] 里没有的类型不代表归零。全量替换的写法（`current + (id to counts)`）
     * 会让一次超时把徽章集体清零，而点进去看到的却是上一轮的数据。
     */
    internal fun mergeObjectCounts(connectionId: String, counts: Map<MqObjectType, Int>) {
        objectCounts.update { current ->
            current + (connectionId to (current[connectionId].orEmpty() + counts))
        }
    }

    internal fun clearObjectCounts(connectionId: String) {
        objectCounts.update { current -> current - connectionId }
    }


    /**
     * 幂等：重复调用返回同一个 [IbmMQService]，不会重复注册订阅。
     */
    @Synchronized
    fun initialize(context: ServiceContext): IbmMQService {
        service?.let { return it }

        // 越早越好：MQ 的 JMQI 可能把 com.ibm.mq.cfg.* 属性在首次类加载时缓存，
        // 任何 MQ 类被触碰之前就必须把 cipher 映射关掉（OpenJDK 只有标准 TLS_* 名）。
        System.setProperty(USE_IBM_CIPHER_MAPPINGS, "false")
        // 同一时机约束：MQ 客户端在首次类加载时读取日志属性并缓存，晚了就会落 cwd。
        IbmMQClientLogConfigurator.init()

        val handler = IbmMQEventHandler()
        val created = IbmMQService(context)
        val connectionManager = IbmMQConnectionManager(context, created)
        // MQSC 控制台的执行器：历史仓库是模块级单例（内存版），生命周期跟模块走。
        mqscExecutor = MqscExecutor(MqscPcfSender(), MqscHistoryRepository())

        subscriptions = listOf(
            // 自己发、自己收：只有 ibm-mq.* 会进这里。
            context.onOwnEvents { event -> handler.handle(event) },
            // 跨模块：核心广播连接变化，只处理属于本模块的连接（payload 里 `type`
            // 即 serviceId，见 ConnectionStore.emitChange 的文档）。
            context.onEvent(EventNames.CONNECTION_ADDED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                if (!isOwnConnection(event, context)) return@onEvent
                // 只登记，不连接 —— 显式连接模型的核心。
                ownConnectionById(connectionId)?.let { connectionManager.ensureContext(it) }
            },
            context.onEvent(EventNames.CONNECTION_UPDATED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                if (!isOwnConnection(event, context)) return@onEvent
                ownConnectionById(connectionId)?.let { connectionManager.updateContextConfig(it) }
            },
            // 连接快照（mq_connection 表，供 operation_history 关联）：payload 组装在本模块
            // （只有它认识 options 里的 qmgr / username 键），写库在 app-core 的
            // MqConnectionSnapshotHandler —— 见 IbmMQConnectionSnapshotEmitter。编辑连接时
            // core 换发新 id（本订阅收到的 added 即新快照），不另听 updated。
            context.onEvent(EventNames.CONNECTION_ADDED) { event ->
                if (isOwnConnection(event, context)) {
                    IbmMQConnectionSnapshotEmitter.onConnectionSaved(event, context)
                }
            },
            context.onEvent(EventNames.CONNECTION_DELETED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                if (!isOwnConnection(event, context)) return@onEvent
                // 断开（含任务与 cache 清理）+ 摘除上下文 + 清模块内记账（含徽章数量）。
                connectionManager.remove(connectionId)
                handler.cleanConnectionData(connectionId)
                clearObjectCounts(connectionId)
            },
            // ---- 显式动作请求（宿主 app-ui 的右键菜单，事件约定见 ConnectionActions）----
            // 连接的 handler 挂起执行到 connect 完成：emit 是异步投递，但 handler
            // 不跑完事件就不算消费 ——「查看属性先连接」的发起方订阅的是
            // ConnectionStore 的状态变化，这里只保证请求被真正执行。
            context.onEvent(IbmMQEvents.CONNECT_REQUESTED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                connectionManager.connect(connectionId)
            },
            context.onEvent(IbmMQEvents.DISCONNECT_REQUESTED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                connectionManager.disconnectNow(connectionId, reason = "menu request")
            },
            context.onEvent(IbmMQEvents.TEST_REQUESTED) { event ->
                handleTestRequested(event, connectionManager, created)
            },
        )

        // 启动时已经在配置里的连接：补登记 DISCONNECTED 上下文。**不连接** ——
        // 「启动即连」是旧模型的行为，显式连接模型下等用户动手。
        ConnectionStore.connections.value
            .filter { it.serviceId == context.moduleId }
            .forEach { connectionManager.ensureContext(it) }

        service = created
        manager = connectionManager
        moduleContext = context
        ibmMqLog.info(
            "module initialized (moduleId=${context.moduleId}, " +
                "contexts=${connectionManager.allContexts().size}, all disconnected)",
        )
        return created
    }

    /** 已初始化的业务门面；未初始化时为 null。 */
    @Synchronized
    fun serviceOrNull(): IbmMQService? = service

    /**
     * 一条连接的运行时上下文（状态 / cache / MQ 句柄的持有者）；未登记时为 null。
     * [IbmMQService] 经它读写扫描缓存。
     */
    fun connectionOrNull(connectionId: String): IbmMQConnectionContext? =
        manager?.contextOrNull(connectionId)

    /**
     * UI 的显式连接入口（连接按钮 / 点击对象类型）：在模块 scope 里跑
     * [IbmMQConnectionManager.connect]，结果经状态上报与事件通知，不阻塞点击。
     */
    fun connectAsync(connectionId: String) {
        val connectionManager = manager ?: return
        moduleContext?.coroutineScope?.launch { connectionManager.connect(connectionId) }
    }

    /**
     * UI 的断开入口（fire-and-forget）。
     */
    fun disconnectAsync(connectionId: String) {
        manager?.disconnectAsync(connectionId)
    }

    /** MQSC 执行器；模块未初始化时为 null（UI 侧据此回落占位页）。 */
    fun mqscExecutorOrNull(): MqscExecutor? = mqscExecutor

    /**
     * 模块销毁：先断开全部连接（取消任务 / 关句柄 / 清 cache），再取消订阅，
     * 最后摘掉引用。顺序反了的话断开过程中发出的 `ibm-mq.*` 事件会无人处理。
     */
    @Synchronized
    fun shutdown() {
        val subscriptionsToCancel = subscriptions
        val managerToStop = manager
        subscriptions = emptyList()
        manager = null
        service = null
        mqscExecutor = null
        try {
            // 模块销毁是同步出口（ModuleRegistry.disposeServices 不挂起）：
            // runBlocking 等全部连接断完再返回 —— 退出路径不能留下没关的句柄。
            managerToStop?.let { manager -> runBlocking { manager.shutdownAll() } }
        } finally {
            subscriptionsToCancel.forEach(Job::cancel)
            moduleContext = null
        }
    }

    /**
     * 「测试连接」请求的处理：取该连接的配置快照发起一次**临时连接**（用完即关，
     * 不影响正式连接的状态与定时任务），结果发 [IbmMQEvents.TEST_COMPLETED] ——
     * 成功失败都发，宿主据此弹结果对话框。配置未登记（上下文不存在）按失败上报。
     */
    private suspend fun handleTestRequested(
        event: com.mqclient.core.event.AppEvent,
        connectionManager: IbmMQConnectionManager,
        service: IbmMQService,
    ) {
        val connectionId = event.getString("connectionId") ?: return
        val config = connectionManager.contextOrNull(connectionId)?.config
        if (config == null) {
            moduleContext?.emit(
                name = IbmMQEvents.TEST_COMPLETED,
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
        // 超时 / 连接失败 → Result.failure（保持原 runCatching 的业务语义）；
        // 真正的协程取消（模块 shutdown 等）必须原样上抛，否则取消语义被吞：
        // emit/log 继续跑、外层结构化并发以为任务还在。
        val outcome = try {
            Result.success(withTimeout(TEST_TIMEOUT_MS) { service.testConnection(config) })
        } catch (e: TimeoutCancellationException) {
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        moduleContext?.emit(
            name = IbmMQEvents.TEST_COMPLETED,
            payload = mapOf(
                "connectionId" to connectionId,
                "success" to outcome.isSuccess,
                "message" to (outcome.exceptionOrNull()?.let { it.message ?: it::class.simpleName } ?: ""),
                "durationMs" to (System.currentTimeMillis() - startedAt),
            ),
        )
        ibmMqLog.info(
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
