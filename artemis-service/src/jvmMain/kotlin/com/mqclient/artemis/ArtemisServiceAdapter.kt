package com.mqclient.artemis

import com.mqclient.mq.base.MQServiceAdapter

class ArtemisServiceAdapter : MQServiceAdapter() {
    override val id = MODULE_ID // 模块名唯一定义处：ArtemisModule.kt 顶层 const
    override val name = "Artemis"
    override val description = "Artemis connection and management"
    override val protocol = "AMQP / OpenWire"
    // category = "MQ" 由 MQServiceAdapter 统一提供
}
