package com.mqclient.ibmmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getString
import com.mqclient.core.log.Log
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.scheduler.ScheduledJob
import com.mqclient.core.spi.MODULE_ID_KEY
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.onEvent
import com.mqclient.core.spi.onOwnEvents
import kotlinx.coroutines.Job
import kotlin.jvm.Synchronized

/**
 * 本模块的模块名 —— **模块内唯一定义处**：`ServiceAdapter.id`、任务 ID 前缀、
 * 日志 tag 全部由它派生，不再各写各的字面量。
 */
internal const val MODULE_ID = "ibm-mq"

/** 本模块的日志句柄：tag 与 [MODULE_ID] 同源，调用点不需要再写模块名。 */
internal val ibmMqLog = Log.withTag(MODULE_ID)

/**
 * IBM MQ 模块的初始化入口。
 *
 * 由 SPI 发现后经 `IbmMqServiceAdapter.initialize(context)` 调用（`ModuleRegistry` 在
 * 启动时逐个初始化模块），做四件事：
 *  1. 用**前缀订阅**收自己的事件（`ibm-mq.*`），交给 [IbmMQEventHandler]；
 *  2. 订阅别的模块 / 核心的事件 —— `core.connection.added` 给新连接建任务，
 *     `core.connection.deleted` 把那条连接留下的任务和数据一起收掉；
 *  3. 给每条连接注册两条周期性扫描任务（消息数量 / 对象列表）；
 *  4. 按「连接状态上报」约定把连接状态报给核心（[ConnectionStore.reportStatus]）：
 *     建任务时是「连接中...」，第一条扫描结果回来才定 已连接 / 连接失败
 *     （见 [IbmMQService.scanMessageCounts]）。
 *
 * 订阅挂在 `context.coroutineScope`（模块自己的 scope）上，返回的 [Job] 由本对象持有；
 * 定时任务挂在 `context.jobScheduler` 上，用任务 ID 前缀 `ibm-mq.` 圈住。[shutdown]
 * 时两者一起取消 —— 即「模块销毁时取消自己的订阅与任务」。
 */
object IbmMQModule {

    /** 任务类型（ID 的第三段）：`ibm-mq.<connectionId>.scan.messages`。 */
    private const val JOB_SCAN_MESSAGES = "scan.messages"
    private const val JOB_SCAN_OBJECTS = "scan.objects"

    /** 消息数量变化快，对象列表变化慢 —— 两个间隔差着两个数量级。 */
    private const val INTERVAL_5S = 5_000L
    private const val INTERVAL_10MIN = 10 * 60 * 1_000L

    /** 对象列表晚 10 秒起步：先把 5 秒那条链路让出来，别在启动瞬间挤在一起。 */
    private const val OBJECTS_FIRST_DELAY = 10_000L

    private var service: IbmMQService? = null
    private var scheduler: JobScheduler? = null
    private var subscriptions: List<Job> = emptyList()

    /**
     * 幂等：重复调用返回同一个 [IbmMQService]，不会重复注册订阅。
     */
    @Synchronized
    fun initialize(context: ServiceContext): IbmMQService {
        service?.let { return it }

        val handler = IbmMQEventHandler()
        val created = IbmMQService(context)
        val jobScheduler = context.jobScheduler

        subscriptions = listOf(
            // 自己发、自己收：只有 ibm-mq.* 会进这里。
            context.onOwnEvents { event -> handler.handle(event) },
            // 跨模块：核心广播「连接被删除」，只有属于本模块的连接才处理。
            context.onEvent(EventNames.CONNECTION_DELETED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                if (event.getString(MODULE_ID_KEY) == context.moduleId ||
                    event.getString("type") == context.moduleId
                ) {
                    // 连接没了，它的定时任务也一起停：按前缀一次收干净，
                    // 不用把任务 id 一个个记下来再 cancel。
                    jobScheduler.cancelByPrefix(ScheduledJob.prefixFor(context.moduleId, connectionId))
                    handler.cleanConnectionData(connectionId)
                }
            },
            // 跨模块：新连接建好后给它配一套自己的任务（每条连接一组，互不影响）。
            context.onEvent(EventNames.CONNECTION_ADDED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                // 和上面 DELETED 分支同一套判定：核心按文档只放 `type`，模块经
                // ServiceContext.emit 转发时 payload 里只有 `moduleId`（没有 `type`），
                // 两个都认才不会漏。只看 `type` 的话，后者永远进不来。
                if (event.getString(MODULE_ID_KEY) != context.moduleId &&
                    event.getString("type") != context.moduleId
                ) {
                    return@onEvent
                }
                // 先在界面上显示「连接中...」：真实的连接由扫描链路建立，
                // 第一条扫描结果（scanMessageCounts）回来才定 已连接 / 连接失败。
                reportStatus(connectionId, ConnectionStatus.Connecting)
                registerJobsForConnection(jobScheduler, created, connectionId)
            },
        )

        // 启动时已经在配置里的连接：[ConnectionStore] 是「现在有哪些」，而
        // `core.connection.added` 是「刚发生了什么」。只订阅事件的话，重启之后要等
        // 用户再新建一条连接才会有定时任务，所以这里按当前状态补一遍。
        // （登记是幂等的：同 ID 重复注册只会顶掉旧的，不会跑两条协程。）
        ConnectionStore.connections.value
            .filter { it.serviceId == context.moduleId }
            .forEach { connection ->
                reportStatus(connection.id, ConnectionStatus.Connecting)
                registerJobsForConnection(jobScheduler, created, connection.id)
            }

        service = created
        scheduler = jobScheduler
        ibmMqLog.info(
            "module initialized (moduleId=${context.moduleId}, " +
                "jobs=${jobScheduler.getActiveJobs().filter { it.startsWith("$MODULE_ID.") }})",
        )
        return created
    }

    /** 已初始化的业务门面；未初始化时为 null。 */
    @Synchronized
    fun serviceOrNull(): IbmMQService? = service

    /**
     * 模块销毁：取消订阅、按前缀停掉本模块的全部定时任务，并释放服务资源。
     *
     * 先把状态摘干净再逐个释放：释放过程中抛错也不会让本对象停在「半关闭」状态，
     * try/finally 保证后面几步照样执行。
     */
    @Synchronized
    fun shutdown() {
        val subscriptionsToCancel = subscriptions
        val schedulerToStop = scheduler
        val serviceToClose = service
        subscriptions = emptyList()
        scheduler = null
        service = null
        try {
            subscriptionsToCancel.forEach(Job::cancel)
        } finally {
            try {
                // 任务 ID 的第一段就是模块名，所以「本模块的任务」= `ibm-mq.` 前缀。
                schedulerToStop?.cancelByPrefix("$MODULE_ID.")
            } finally {
                serviceToClose?.close()
            }
        }
    }

    /**
     * 按「连接状态上报」约定把状态报给核心（连接数据的唯一入口，见
     * [ConnectionStore.reportStatus]）：模块只报事实，界面怎么画不归这里管。
     * 状态没变时核心直接忽略，所以定时链路重复上报是安全的。
     */
    private fun reportStatus(connectionId: String, status: ConnectionStatus) {
        ConnectionStore.reportStatus(connectionId, status, MODULE_ID)
    }

    /**
     * 给一条连接注册它的全部定时任务。
     *
     * 幂等：同 ID 重复注册会顶掉旧的（见 [JobScheduler.schedule]），所以「启动时补注册」
     * 和「事件触发的注册」可以并存，不会出现两条协程跑同一个任务。
     */
    private fun registerJobsForConnection(
        scheduler: JobScheduler,
        service: IbmMQService,
        connectionId: String,
    ) {
        // 任务 1：每 5 秒扫一次消息数量 —— UI 最关心的数字，变化快。
        scheduler.schedule(
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, connectionId, JOB_SCAN_MESSAGES),
                intervalMs = INTERVAL_5S,
                runOnStart = true,
                action = { service.scanMessageCounts(connectionId) },
            ),
        )

        // 任务 2：每 10 分钟扫一次对象列表（队列 / 主题 / 监听器）—— 变化慢。
        scheduler.schedule(
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, connectionId, JOB_SCAN_OBJECTS),
                intervalMs = INTERVAL_10MIN,
                initialDelayMs = OBJECTS_FIRST_DELAY,
                action = { service.scanObjects(connectionId) },
            ),
        )
    }
}
