package com.mqclient.kafka

import com.mqclient.core.BusinessService

class KafkaService : BusinessService {
    override val id = "kafka"
    override val name = "Kafka"
    override val description = "Kafka connection and management"
}
