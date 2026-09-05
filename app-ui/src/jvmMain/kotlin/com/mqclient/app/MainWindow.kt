package com.mqclient.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.material3.adaptive.navigationsuite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.ModuleRegistry

/**
 * Material's expanded-width breakpoint. At or above it the module list gets a
 * permanent drawer; below it the same list collapses into a navigation rail.
 */
private val DrawerWidthThreshold = 840.dp

private val StatusBarHeight = 28.dp

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
@Composable
fun MainWindow(isNavigationVisible: Boolean) {
    val services = remember { ModuleRegistry.services }
    val uiByService = remember { ModuleRegistry.uis.associateBy { it.serviceId } }

    var selectedId by remember { mutableStateOf(services.firstOrNull()?.id) }

    Column(modifier = Modifier.fillMaxSize()) {
        NavigationSuiteScaffold(
            modifier = Modifier.weight(1f),
            layoutType =
                when {
                    !isNavigationVisible -> NavigationSuiteType.None
                    currentWindowDpSize().width >= DrawerWidthThreshold -> NavigationSuiteType.NavigationDrawer
                    else -> NavigationSuiteType.NavigationRail
                },
            navigationSuiteItems = {
                // Built from the SPI-discovered services.
                services.forEach { svc ->
                    item(
                        selected = svc.id == selectedId,
                        onClick = { selectedId = svc.id },
                        // Icon slot is intentionally empty for now. The Material
                        // Symbols pipeline is in place (Res.drawable.*), it just
                        // needs the per-module artwork picked.
                        icon = {},
                        label = { Text(svc.name) },
                    )
                }
            },
        ) {
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
        StatusBar(currentModuleName = services.firstOrNull { it.id == selectedId }?.name)
    }
}

@Composable
private fun StatusBar(currentModuleName: String?) {
    Column {
        HorizontalDivider()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(StatusBarHeight)
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = currentModuleName ?: "No module selected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Kept free for the connection/queue counters that come later.
            Spacer(Modifier.weight(1f))
        }
    }
}
