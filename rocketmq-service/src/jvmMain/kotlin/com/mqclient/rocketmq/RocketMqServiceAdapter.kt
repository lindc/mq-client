package com.mqclient.rocketmq

import com.mqclient.mq.base.MQServiceAdapter

class RocketMqServiceAdapter : MQServiceAdapter() {
    override val id = MODULE_ID // 模块名唯一定义处：RocketMqModule.kt 顶层 const
    override val name = "RocketMQ"
    override val description = "RocketMQ connection and management"
    override val protocol = "Remoting"
    // category = "MQ" 由 MQServiceAdapter 统一提供
}
