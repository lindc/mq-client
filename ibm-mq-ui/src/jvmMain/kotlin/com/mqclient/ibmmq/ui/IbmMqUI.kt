package com.mqclient.ibmmq.ui

import androidx.compose.runtime.Composable
import com.mqclient.core.AdapterUI
import androidx.compose.material3.Text

class IbmMqUI : AdapterUI {
    override val serviceId = "ibm-mq"
    override val title = "IBM MQ"
    override val content: @Composable () -> Unit = { Text("IBM MQ — coming soon") }
}
