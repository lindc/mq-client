package com.mqclient.ibmmq

import com.mqclient.core.ServiceAdapter

class IbmMqServiceAdapter : ServiceAdapter {
    override val id = "ibm-mq"
    override val name = "IBM MQ"
    override val description = "IBM MQ connection and management"
    override val protocol = "MQI"
}
