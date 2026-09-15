package com.mqclient.ibmmq

import com.mqclient.core.ServiceAdapter
import com.mqclient.core.spi.ServiceContext

class IbmMqServiceAdapter : ServiceAdapter {
    override val id = MODULE_ID // 模块名唯一定义处：IbmMQModule.kt 顶层 const
    override val name = "IBM MQ"
    override val description = "IBM MQ connection and management"
    override val protocol = "MQI"
    override val category = "MQ"

    /**
     * SPI 发现后由 ModuleRegistry 调用：注册本模块的事件订阅
     * （`ibm-mq.*` 前缀订阅 + 核心 `core.connection.deleted` 跨模块订阅）。
     */
    override fun initialize(context: ServiceContext) {
        IbmMQModule.initialize(context)
    }

    /**
     * SPI 模块销毁：应用退出时由 ModuleRegistry 在 CoreModule 停机之前调用，
     * 转给 [IbmMQModule.shutdown] 取消本模块的订阅、定时任务并释放服务资源。
     */
    override fun dispose() {
        IbmMQModule.shutdown()
    }
}
