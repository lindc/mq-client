package com.mqclient.rabbitmq.ui

import androidx.compose.runtime.Composable
import com.mqclient.core.BusinessUi
import androidx.compose.material3.Text

object RabbitMqUi : BusinessUi {
    override val serviceId = "rabbitmq"
    override val title = "RabbitMQ"
    override val content: @Composable () -> Unit = { Text("RabbitMQ — coming soon") }
}
