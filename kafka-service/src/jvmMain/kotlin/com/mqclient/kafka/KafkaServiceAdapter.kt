package com.mqclient.kafka

import com.mqclient.core.ServiceAdapter

class KafkaServiceAdapter : ServiceAdapter {
    override val id = "kafka"
    override val name = "Kafka"
    override val description = "Kafka connection and management"
    override val protocol = "Kafka Protocol"
}
