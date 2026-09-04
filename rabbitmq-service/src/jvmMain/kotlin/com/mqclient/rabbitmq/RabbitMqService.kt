package com.mqclient.rabbitmq

import com.mqclient.core.BusinessService

class RabbitMqService : BusinessService {
    override val id = "rabbitmq"
    override val name = "RabbitMQ"
    override val description = "RabbitMQ connection and management"
}
