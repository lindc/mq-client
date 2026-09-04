package com.mqclient.artemis.ui

import androidx.compose.runtime.Composable
import com.mqclient.core.BusinessUi
import androidx.compose.material3.Text

object ArtemisUi : BusinessUi {
    override val serviceId = "artemis"
    override val title = "Artemis"
    override val content: @Composable () -> Unit = { Text("Artemis — coming soon") }
}
