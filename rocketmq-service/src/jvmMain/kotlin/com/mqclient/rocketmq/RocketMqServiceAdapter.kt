package com.mqclient.rocketmq

import com.mqclient.core.ServiceAdapter

class RocketMqServiceAdapter : ServiceAdapter {
    override val id = "rocketmq"
    override val name = "RocketMQ"
    override val description = "RocketMQ connection and management"
    override val protocol = "Remoting"
}
