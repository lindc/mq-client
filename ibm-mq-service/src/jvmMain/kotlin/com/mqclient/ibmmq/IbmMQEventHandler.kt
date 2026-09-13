package com.mqclient.ibmmq

import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.getInt
import com.mqclient.core.event.getString
import com.mqclient.ibmmq.event.IbmMQEvents
import java.util.concurrent.ConcurrentHashMap

/**
 * IBM MQ 模块自己的事件处理器。
 *
 * 「自己发、自己收、自己处理」：模块产生的 `ibm-mq.*` 事件同样走全局总线，模块内部
 * 逻辑却和别的订阅方（UI、日志、审计）完全解耦 —— 谁在看这个事件，这里一无所知。
 *
 * 线程安全：同一条订阅的 handler 是串行调用的，但本模块同时注册了「前缀订阅」和
 * 「核心 CONNECTION_DELETED 订阅」两条，它们可能并发进入本类，所以内部状态用
 * [ConcurrentHashMap]。
 */
class IbmMQEventHandler {

    /** connectionId → 最近一次扫描看到的队列数；示例性的模块内部记账。 */
    private val queueCounts = ConcurrentHashMap<String, Int>()

    /** [IbmMQModule] 在 `ibm-mq.*` 前缀订阅里对每个事件调用它。 */
    suspend fun handle(event: AppEvent) {
        when (event.name) {
            IbmMQEvents.SCAN_STARTED -> handleScanStarted(event)
            IbmMQEvents.SCAN_COMPLETED -> handleScanCompleted(event)
            IbmMQEvents.SCAN_FAILED -> handleScanFailed(event)
            // 定时任务（JobScheduler）触发的扫描：和手动扫描分开记，一眼能看出是哪条链路。
            IbmMQEvents.MESSAGES_SCANNED -> handleMessagesScanned(event)
            IbmMQEvents.OBJECTS_SCANNED -> handleObjectsScanned(event)
            // 两类定时扫描的失败 payload 形状一致（connectionId / message），复用同一个处理。
            IbmMQEvents.MESSAGES_SCAN_FAILED, IbmMQEvents.OBJECTS_SCAN_FAILED -> handleScanFailed(event)
            // 未知事件忽略即可：新事件在 IbmMQEvents 里加一行常量、这里加一个分支，
            // 事件总线不需要注册任何东西。
            else -> Unit
        }
    }

    /** 连接被删除时清掉本模块为它留下的数据（由跨模块订阅触发）。 */
    fun cleanConnectionData(connectionId: String) {
        val removed = queueCounts.remove(connectionId)
        ibmMqLog.info("cleaned local data for connection=$connectionId (was $removed)")
    }

    private fun handleScanStarted(event: AppEvent) {
        ibmMqLog.info("scan started: connection=${event.getString("connectionId") ?: "?"}")
    }

    private fun handleScanCompleted(event: AppEvent) {
        val connectionId = event.getString("connectionId") ?: return
        val queueCount = event.getInt("queueCount") ?: 0
        queueCounts[connectionId] = queueCount
        ibmMqLog.info(
            "scan completed: connection=$connectionId " +
                "queues=$queueCount messages=${event.getInt("totalMessages") ?: 0}",
        )
    }

    private fun handleScanFailed(event: AppEvent) {
        val connectionId = event.getString("connectionId") ?: return
        ibmMqLog.warn(
            "scan failed: connection=$connectionId " +
                "reason=${event.getString("message") ?: "unknown"}",
        )
    }

    /** 定时扫描完消息数量：顺手把队列数记下来，和手动扫描共用同一份记账。 */
    private fun handleMessagesScanned(event: AppEvent) {
        val connectionId = event.getString("connectionId") ?: return
        val queueCount = event.getInt("queueCount") ?: 0
        queueCounts[connectionId] = queueCount
        ibmMqLog.debug(
            "scheduled scan (messages): connection=$connectionId " +
                "queues=$queueCount messages=${event.getInt("totalMessages") ?: 0}",
        )
    }

    private fun handleObjectsScanned(event: AppEvent) {
        ibmMqLog.info(
            "scheduled scan (objects): connection=${event.getString("connectionId") ?: "?"} " +
                "queues=${event.getInt("queueCount") ?: 0} topics=${event.getInt("topicCount") ?: 0} " +
                "listeners=${event.getInt("listenerCount") ?: 0}",
        )
    }
}
