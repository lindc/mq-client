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
            // 未知事件忽略即可：新事件在 IbmMQEvents 里加一行常量、这里加一个分支，
            // 事件总线不需要注册任何东西。
            else -> Unit
        }
    }

    /** 连接被删除时清掉本模块为它留下的数据（由跨模块订阅触发）。 */
    fun cleanConnectionData(connectionId: String) {
        val removed = queueCounts.remove(connectionId)
        println("[ibm-mq] cleaned local data for connection=$connectionId (was $removed)")
    }

    private fun handleScanStarted(event: AppEvent) {
        println("[ibm-mq] scan started: connection=${event.getString("connectionId") ?: "?"}")
    }

    private fun handleScanCompleted(event: AppEvent) {
        val connectionId = event.getString("connectionId") ?: return
        val queueCount = event.getInt("queueCount") ?: 0
        queueCounts[connectionId] = queueCount
        println(
            "[ibm-mq] scan completed: connection=$connectionId " +
                "queues=$queueCount messages=${event.getInt("totalMessages") ?: 0}",
        )
    }

    private fun handleScanFailed(event: AppEvent) {
        val connectionId = event.getString("connectionId") ?: return
        println(
            "[ibm-mq] scan failed: connection=$connectionId " +
                "reason=${event.getString("message") ?: "unknown"}",
        )
    }
}
