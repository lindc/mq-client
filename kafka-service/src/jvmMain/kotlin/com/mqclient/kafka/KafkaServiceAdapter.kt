package com.mqclient.kafka

import com.mqclient.mq.base.MQServiceAdapter

class KafkaServiceAdapter : MQServiceAdapter() {
    override val id = MODULE_ID // 模块名唯一定处：KafkaModule.kt 顶层 const
    override val name = "Kafka"
    override val description = "Kafka connection and management"
    override val protocol = "Kafka Protocol"
    // category = "MQ" 由 MQServiceAdapter 统一提供
}
