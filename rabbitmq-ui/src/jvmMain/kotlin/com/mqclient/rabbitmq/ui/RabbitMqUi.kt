package com.mqclient.rabbitmq.ui

import androidx.compose.runtime.Composable
import com.mqclient.core.BusinessUi
import androidx.compose.material3.Text

// Must be a class, not an object: ServiceLoader instantiates providers through
// their public no-arg constructor, which a Kotlin `object` does not have.
class RabbitMqUi : BusinessUi {
    override val serviceId = "rabbitmq"
    override val title = "RabbitMQ"
    override val content: @Composable () -> Unit = { Text("RabbitMQ — coming soon") }
}
