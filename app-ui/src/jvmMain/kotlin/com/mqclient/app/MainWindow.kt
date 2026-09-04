package com.mqclient.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ModuleRegistry

@Composable
fun MainWindow() {
    val services = remember { ModuleRegistry.services }
    val uiByService = remember { ModuleRegistry.uis.associateBy { it.serviceId } }

    var selectedId by remember { mutableStateOf(services.firstOrNull()?.id) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Sidebar built from the SPI-discovered services.
            Column(
                modifier =
                    Modifier
                        .width(220.dp)
                        .fillMaxHeight()
                        .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("MQ Client", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                services.forEach { svc ->
                    val selected = svc.id == selectedId
                    Text(
                        text = svc.name,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { selectedId = svc.id }
                                .padding(8.dp),
                    )
                }
            }

            // Content area: renders the selected module's UI contribution.
            Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                val ui = selectedId?.let { uiByService[it] }
                if (ui != null) {
                    ui.content()
                } else {
                    Text("Select a module from the sidebar", modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}
