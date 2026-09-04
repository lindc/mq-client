package com.mqclient.rocketmq.ui

import androidx.compose.runtime.Composable
import com.mqclient.core.BusinessUi
import dev.nucleusframework.macoscompose.components.Text

object RocketMqUi : BusinessUi {
    override val serviceId = "rocketmq"
    override val title = "RocketMQ"
    override val content: @Composable () -> Unit = { Text("RocketMQ — coming soon") }
}
