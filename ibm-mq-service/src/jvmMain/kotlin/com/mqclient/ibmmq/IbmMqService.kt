package com.mqclient.ibmmq

import com.mqclient.core.BusinessService

class IbmMqService : BusinessService {
    override val id = "ibm-mq"
    override val name = "IBM MQ"
    override val description = "IBM MQ connection and management"
}
