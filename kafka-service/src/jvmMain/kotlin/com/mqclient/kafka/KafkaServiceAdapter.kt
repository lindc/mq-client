package com.mqclient.kafka

import com.mqclient.core.ServiceAdapter

class KafkaServiceAdapter : ServiceAdapter {
    override val id = MODULE_ID // 模块名唯一定义处：KafkaModule.kt 顶层 const
    override val name = "Kafka"
    override val description = "Kafka connection and management"
    override val protocol = "Kafka Protocol"
    override val category = "MQ"
}
