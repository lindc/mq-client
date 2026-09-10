package com.mqclient.ibmmq

import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getString
import com.mqclient.core.spi.MODULE_ID_KEY
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.onEvent
import com.mqclient.core.spi.onOwnEvents
import kotlinx.coroutines.Job
import kotlin.jvm.Synchronized

/**
 * IBM MQ 模块的初始化入口。
 *
 * 由 SPI 发现后经 `IbmMqServiceAdapter.initialize(context)` 调用（`ModuleRegistry` 在
 * 启动时逐个初始化模块），做两件事：
 *  1. 用**前缀订阅**收自己的事件（`ibm-mq.*`），交给 [IbmMQEventHandler]；
 *  2. 订阅别的模块 / 核心的事件 —— 这里监听 `core.connection.deleted`，把本模块为
 *     那条连接留下的数据清掉。
 *
 * 两条订阅都挂在 `context.coroutineScope`（模块自己的 scope）上，返回的 [Job] 由本
 * 对象持有，[shutdown] 时统一 cancel —— 即「模块销毁时取消自己的订阅」。
 */
object IbmMQModule {

    private var service: IbmMQService? = null
    private var subscriptions: List<Job> = emptyList()

    /**
     * 幂等：重复调用返回同一个 [IbmMQService]，不会重复注册订阅。
     */
    @Synchronized
    fun initialize(context: ServiceContext): IbmMQService {
        service?.let { return it }

        val handler = IbmMQEventHandler()

        subscriptions = listOf(
            // 自己发、自己收：只有 ibm-mq.* 会进这里。
            context.onOwnEvents { event -> handler.handle(event) },
            // 跨模块：核心广播「连接被删除」，只有属于本模块的连接才处理。
            context.onEvent(EventNames.CONNECTION_DELETED) { event ->
                val connectionId = event.getString("connectionId") ?: return@onEvent
                if (event.getString(MODULE_ID_KEY) == context.moduleId ||
                    event.getString("type") == context.moduleId
                ) {
                    handler.cleanConnectionData(connectionId)
                }
            },
        )

        val created = IbmMQService(context)
        service = created
        println("[ibm-mq] module initialized (moduleId=${context.moduleId})")
        return created
    }

    /** 已初始化的业务门面；未初始化时为 null。 */
    fun serviceOrNull(): IbmMQService? = service

    /** 模块销毁：取消自己注册的全部订阅，并停掉在跑的扫描。 */
    @Synchronized
    fun shutdown() {
        subscriptions.forEach(Job::cancel)
        subscriptions = emptyList()
        service?.close()
        service = null
    }
}
