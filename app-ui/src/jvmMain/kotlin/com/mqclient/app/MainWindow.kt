package com.mqclient.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

@OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
@Composable
fun MainWindow() {
    val services = remember { ModuleRegistry.services }
    val uiByService = remember { ModuleRegistry.uis.associateBy { it.serviceId } }

    var selectedId by remember { mutableStateOf(services.firstOrNull()?.id) }

    NavigationSuiteScaffold(
        layoutType =
            if (currentWindowDpSize().width >= DrawerWidthThreshold) {
                NavigationSuiteType.NavigationDrawer
            } else {
                NavigationSuiteType.NavigationRail
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
}
