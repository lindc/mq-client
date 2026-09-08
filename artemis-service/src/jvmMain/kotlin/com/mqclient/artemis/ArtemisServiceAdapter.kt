package com.mqclient.artemis

import com.mqclient.core.ServiceAdapter

class ArtemisServiceAdapter : ServiceAdapter {
    override val id = "artemis"
    override val name = "Artemis"
    override val description = "Artemis connection and management"
    override val protocol = "AMQP / OpenWire"
}
