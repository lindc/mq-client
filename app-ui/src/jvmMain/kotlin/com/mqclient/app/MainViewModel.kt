package com.mqclient.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.event.EventBus
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getInt
import com.mqclient.core.event.getString
import com.mqclient.core.spi.MODULE_ID_KEY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 跨模块监听的示例：主界面不关心事件是哪个模块发的，只关心「发生了什么」。
 *
 * 两个要点：
 *  1. 订阅用的是**后缀**（`.scan.completed` / `.error.occurred`），所以 IBM MQ、
 *     RabbitMQ、Kafka … 谁的扫描完成都会进来，将来加模块不用改这里；
 *  2. 订阅挂在 [Dispatchers.Main] 上 —— 这正是事件总线把调度权交给订阅方的原因。
 *     如果总线强制所有 handler 走 Dispatchers.Default，这里每次写 `mutableStateOf`
 *     都得自己切回主线程。
 *
 * ⚠️ 示例代码，暂未接线：在 MQ 面板入口 `remember { MainViewModel(CoreModule.eventBus) }`
 * 即可启用，离开界面时调用 [close]（或把它交给自己的 scope）。
 */
class MainViewModel(private val eventBus: EventBus) {

    /** 界面自己的 scope：Compose 状态只能在主线程写。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val subscriptions = mutableListOf<Job>()

    /** 最近一次（任意模块的）扫描结果摘要，供界面展示。 */
    var lastScanSummary by mutableStateOf<ScanSummary?>(null)
        private set

    /** 待展示的错误信息；界面读完后调 [dismissError]。 */
    var errorMessage by mutableStateOf<String?>(null)
        private set

    init {
        // 所有模块的「扫描完成」都收 —— payload 里的 moduleId 由 ServiceContext.emit 填好。
        subscriptions += eventBus.onSuffix(".scan.completed", scope) { event ->
            lastScanSummary = ScanSummary(
                moduleId = event.getString(MODULE_ID_KEY) ?: event.source ?: "unknown",
                connectionId = event.getString("connectionId").orEmpty(),
                queueCount = event.getInt("queueCount") ?: 0,
                totalMessages = event.getInt("totalMessages") ?: 0,
            )
        }

        // 所有模块的错误事件都收（模块自己发 .error.occurred 即可被这里看到）。
        subscriptions += eventBus.onSuffix(".error.occurred", scope) { event ->
            errorMessage = event.getString("message") ?: "未知错误"
        }

        // 精确订阅核心事件：连接被删掉时收起指向它的内容。
        subscriptions += eventBus.on(EventNames.CONNECTION_DELETED, scope) { event ->
            val deleted = event.getString("connectionId") ?: return@on
            if (lastScanSummary?.connectionId == deleted) lastScanSummary = null
        }
    }

    fun dismissError() {
        errorMessage = null
    }

    /** 界面销毁：取消本 VM 的全部订阅。模块的订阅由模块自己管（各自的 Job）。 */
    fun close() {
        subscriptions.forEach(Job::cancel)
        subscriptions.clear()
        scope.cancel()
    }
}

/** 界面展示用的扫描摘要（示例 DTO）。 */
data class ScanSummary(
    val moduleId: String,
    val connectionId: String,
    val queueCount: Int,
    val totalMessages: Int,
)
