package com.mqclient.rocketmq

import com.mqclient.core.ServiceAdapter

class RocketMqServiceAdapter : ServiceAdapter {
    override val id = MODULE_ID // 模块名唯一定义处：RocketMqModule.kt 顶层 const
    override val name = "RocketMQ"
    override val description = "RocketMQ connection and management"
    override val protocol = "Remoting"
}
