package com.mqclient.ibmmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.event.EventNames
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.ibmmq.event.IbmMQEvents
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 扫描到的队列快照（示例数据结构）。 */
data class QueueSnapshot(val name: String, val depth: Int, val messageCount: Int)

/**
 * IBM MQ 模块的业务逻辑：干活 + 发事件。
 *
 * 业务代码只认 [ServiceContext]（总线 + 模块 scope），不认全局单例，也不关心谁在
 * 监听它发出的事件。
 *
 * ⚠️ 骨架：仓库当前没有任何 IBM MQ 客户端依赖（见 `ibm-mq-service/build.gradle.kts`），
 * 所以 [fetchQueues] 是模拟实现。接真库时只替换它，事件部分不用动。
 */
class IbmMQService(private val context: ServiceContext) {

    /** 同一模块内多次扫描串行执行，避免重叠扫描互相覆盖结果。 */
    private val scanLock = Mutex()

    /**
     * 当前手动扫描的协程。
     *
     * 用 [AtomicReference] 而不是裸 `var`：[startScan] 从界面线程调、[close] 从退出线程
     * 调，「读旧值 → 写新值」这种 check-then-act 没有同步时会丢更新（旧协程漏取消，
     * 继续在后台扫）。
     */
    private val scanJob = AtomicReference<Job?>(null)

    /**
     * 需要显式释放的句柄（真实连接 / 会话 / 消费者 / 通道），key 通常用 connectionId。
     *
     * 仓库里还没有真 MQ 依赖，所以现在它是空的；接真库时在建立连接后调
     * [registerResource] 登记，[close] 负责逐个关掉 —— 「谁负责关」永远只有一个答案。
     */
    private val resources = ConcurrentHashMap<String, AutoCloseable>()

    /** 触发一次扫描；重复调用会取消上一次未完成的扫描。 */
    fun startScan(connectionId: String) {
        val started = context.coroutineScope.launch {
            scanLock.withLock {
                // 事件名来自 IbmMQEvents，payload 里的 moduleId / source 由
                // ServiceContext.emit 自动补上。
                context.emit(IbmMQEvents.SCAN_STARTED, mapOf("connectionId" to connectionId))
                try {
                    val queues = fetchQueues(connectionId)
                    context.emit(
                        name = IbmMQEvents.SCAN_COMPLETED,
                        payload = mapOf(
                            "connectionId" to connectionId,
                            "queueCount" to queues.size,
                            "totalMessages" to queues.sumOf { it.messageCount },
                        ),
                    )
                } catch (cancellation: CancellationException) {
                    // 扫描被取消（模块关闭 / 新扫描顶掉）不算失败，也不发事件。
                    throw cancellation
                } catch (error: Throwable) {
                    context.emit(
                        name = IbmMQEvents.SCAN_FAILED,
                        payload = mapOf(
                            "connectionId" to connectionId,
                            "message" to (error.message ?: error::class.simpleName ?: "unknown"),
                        ),
                    )
                }
            }
        }
        // 先登记新协程、再取消旧的：getAndSet 是原子的，两个线程同时调 startScan 也
        // 只有一方能拿到「上一个」并取消它，不会留下两条并行扫描。
        scanJob.getAndSet(started)?.cancel()
    }

    /**
     * 定时任务入口（`ibm-mq.<connectionId>.scan.messages`，每 5 秒）：扫一次消息数量。
     *
     * 与 [startScan] 共用一把 [scanLock]：定时扫描和用户手动扫描不会同时压到同一条连接上。
     * 业务错误在这里就转成 `*.failed` 事件了 —— 能落到调度器 `JOB_FAILED` 的是「意料之外」
     * 的异常（代码 bug 之类），两者别混为一谈。
     */
    suspend fun scanMessageCounts(connectionId: String) = scanLock.withLock {
        try {
            val queues = fetchQueues(connectionId)
            context.emit(
                name = IbmMQEvents.MESSAGES_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "queueCount" to queues.size,
                    "totalMessages" to queues.sumOf { it.messageCount },
                ),
            )
            // 这条 5 秒链路就是模块的「心跳」：跑通了即这条连接是活的，按「连接状态
            // 上报」约定报给核心（唯一入口，见 ConnectionStore.reportStatus）。
            // 状态没变时核心直接忽略，重复上报不会推着界面重组。
            ConnectionStore.reportStatus(connectionId, ConnectionStatus.Connected, context.moduleId)
        } catch (cancellation: CancellationException) {
            // 模块关闭 / 任务被取消：正常退出，不是失败，也不发事件、不改状态。
            throw cancellation
        } catch (error: Throwable) {
            context.emit(
                name = IbmMQEvents.MESSAGES_SCAN_FAILED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "message" to (error.message ?: error::class.simpleName ?: "unknown"),
                ),
            )
            // 扫描失败即这条连接当前不可用：状态点变「连接失败」，直到下一次扫通为止。
            ConnectionStore.reportStatus(connectionId, ConnectionStatus.Failed, context.moduleId)
        }
    }

    /**
     * 定时任务入口（`ibm-mq.<connectionId>.scan.objects`，每 10 分钟）：扫一次对象列表。
     *
     * 队列 / 主题 / 监听器一次拿全再发一条事件，订阅方（UI）就不会看到「队列已更新、
     * 主题还是旧的」这种中间态。落库交给 [saveObjects]。
     */
    suspend fun scanObjects(connectionId: String) = scanLock.withLock {
        try {
            val queues = fetchQueues(connectionId)
            val topics = fetchTopics(connectionId)
            val listeners = fetchListeners(connectionId)

            saveObjects(connectionId, queues, topics, listeners)

            context.emit(
                name = IbmMQEvents.OBJECTS_SCANNED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "queueCount" to queues.size,
                    "topicCount" to topics.size,
                    "listenerCount" to listeners.size,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            context.emit(
                name = IbmMQEvents.OBJECTS_SCAN_FAILED,
                payload = mapOf(
                    "connectionId" to connectionId,
                    "message" to (error.message ?: error::class.simpleName ?: "unknown"),
                ),
            )
        }
    }

    /**
     * 登记一个要在 [close] 时释放的句柄（真实连接 / 消费者 / 通道）。
     *
     * 同一个 key 重复登记会先把旧句柄关掉 —— 重连场景下不能让上一个连接漏在那里。
     */
    fun registerResource(key: String, resource: AutoCloseable) {
        resources.put(key, resource)?.let { previous ->
            try {
                previous.close()
            } catch (error: Throwable) {
                reportResourceFailure(key, error)
            }
        }
    }

    /**
     * 模块销毁：停掉扫描协程，再逐个释放登记过的资源（订阅的取消由 [IbmMQModule] 负责）。
     *
     * 每个句柄单独 try/catch：一个关不掉不该让其余资源跟着漏掉（这正是 try/finally 要
     * 保证的语义）。失败上报到错误通道，而不是只打一行控制台日志 —— 打包后没人看得到。
     */
    fun close() {
        scanJob.getAndSet(null)?.cancel()
        resources.keys.toList().forEach { key ->
            val handle = resources.remove(key) ?: return@forEach
            try {
                handle.close()
            } catch (error: Throwable) {
                reportResourceFailure(key, error)
            }
        }
    }

    private fun reportResourceFailure(key: String, error: Throwable) {
        context.eventBus.emit(
            name = EventNames.ERROR_OCCURRED,
            payload = mapOf(
                "message" to "释放资源 '$key' 失败：${error.message ?: error::class.simpleName ?: "unknown"}",
                "moduleId" to context.moduleId,
                "error" to error::class.simpleName,
            ),
            source = context.moduleId,
        )
    }

    /**
     * TODO 接真库：换成 IBM MQ 的 PCF 查询（`PCFMessageAgent`）或 JMS 浏览。
     * 现在返回一批假数据，好让 `started → completed` 这条事件链路端到端跑起来。
     */
    private suspend fun fetchQueues(connectionId: String): List<QueueSnapshot> {
        delay(300)
        return listOf(
            QueueSnapshot("$connectionId.DEV.QUEUE1", depth = 12, messageCount = 12),
            QueueSnapshot("$connectionId.DEV.QUEUE2", depth = 0, messageCount = 0),
        )
    }

    /**
     * TODO 接真库：PCF 查询主题列表（`PCFMessageAgent`）或 MQSC `DISPLAY TOPIC`。
     * 现在返回空列表，好让 `scan.objects` 这条定时链路能端到端跑完。
     */
    private suspend fun fetchTopics(connectionId: String): List<String> {
        delay(200)
        return emptyList()
    }

    /** TODO 接真库：MQSC `DISPLAY LISTENER`。 */
    private suspend fun fetchListeners(connectionId: String): List<String> {
        delay(200)
        return emptyList()
    }

    /**
     * TODO 接真库：把对象快照落到本模块自己的表里。
     *
     * 表归模块自己管（见 app-core 的 DatabaseManager）：要落库就在 `ibm-mq-service`
     * 下加自己的 .sq 文件并 apply SQLDelight 插件，不要往 app-core 里塞业务表。
     */
    private suspend fun saveObjects(
        connectionId: String,
        queues: List<QueueSnapshot>,
        topics: List<String>,
        listeners: List<String>,
    ) {
        // 占位：等本模块的 schema 就位再写。
    }
}
