package com.mqclient.artemis.ui

import androidx.compose.runtime.Composable
import com.mqclient.core.AdapterUI
import androidx.compose.material3.Text

class ArtemisUI : AdapterUI {
    override val serviceId = "artemis"
    override val title = "Artemis"
    override val content: @Composable () -> Unit = { Text("Artemis — coming soon") }
}
