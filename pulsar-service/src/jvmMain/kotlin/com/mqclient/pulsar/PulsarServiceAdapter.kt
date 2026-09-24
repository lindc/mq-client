package com.mqclient.pulsar

import com.mqclient.mq.base.MQServiceAdapter

class PulsarServiceAdapter : MQServiceAdapter() {
    override val id = MODULE_ID // 模块名唯一定义处：PulsarModule.kt 顶层 const
    override val name = "Pulsar"
    override val description = "Apache Pulsar connection and management"
    override val protocol = "Pulsar Protocol"
    // category = "MQ" 由 MQServiceAdapter 统一提供
}
