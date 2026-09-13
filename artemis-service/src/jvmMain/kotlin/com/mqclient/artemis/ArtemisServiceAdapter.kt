package com.mqclient.artemis

import com.mqclient.core.ServiceAdapter

class ArtemisServiceAdapter : ServiceAdapter {
    override val id = MODULE_ID // 模块名唯一定义处：ArtemisModule.kt 顶层 const
    override val name = "Artemis"
    override val description = "Artemis connection and management"
    override val protocol = "AMQP / OpenWire"
}
