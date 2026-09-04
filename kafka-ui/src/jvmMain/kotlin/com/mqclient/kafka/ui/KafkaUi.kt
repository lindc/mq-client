package com.mqclient.kafka.ui

import androidx.compose.runtime.Composable
import com.mqclient.core.BusinessUi
import androidx.compose.material3.Text

object KafkaUi : BusinessUi {
    override val serviceId = "kafka"
    override val title = "Kafka"
    override val content: @Composable () -> Unit = { Text("Kafka — coming soon") }
}
