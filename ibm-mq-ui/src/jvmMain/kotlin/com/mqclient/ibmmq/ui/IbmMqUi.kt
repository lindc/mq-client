package com.mqclient.ibmmq.ui

import androidx.compose.runtime.Composable
import com.mqclient.core.BusinessUi
import dev.nucleusframework.macoscompose.components.Text

object IbmMqUi : BusinessUi {
    override val serviceId = "ibm-mq"
    override val title = "IBM MQ"
    override val content: @Composable () -> Unit = { Text("IBM MQ — coming soon") }
}
