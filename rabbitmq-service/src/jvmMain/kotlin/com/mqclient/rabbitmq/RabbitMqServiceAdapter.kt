package com.mqclient.rabbitmq

import com.mqclient.core.ServiceAdapter

class RabbitMqServiceAdapter : ServiceAdapter {
    override val id = MODULE_ID // 模块名唯一定义处：RabbitMqModule.kt 顶层 const
    override val name = "RabbitMQ"
    override val description = "RabbitMQ connection and management"
    override val protocol = "AMQP 0.9.1"
    override val category = "MQ"
}
