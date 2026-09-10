package com.mqclient.ibmmq

import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.ibmmq.event.IbmMQEvents
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

    private var scanJob: Job? = null

    /** 触发一次扫描；重复调用会取消上一次未完成的扫描。 */
    fun startScan(connectionId: String) {
        scanJob?.cancel()
        scanJob = context.coroutineScope.launch {
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
    }

    /** 模块销毁：停掉还没跑完的扫描（订阅的取消由 IbmMQModule 负责）。 */
    fun close() {
        scanJob?.cancel()
        scanJob = null
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
}
