package com.mqclient.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mqclient.core.ModuleRegistry

/**
 * Window widths at or above this get the full tree sidebar; narrower windows
 * collapse the navigation to the [ModuleRail] pill column.
 */
private val WideSidebarThreshold = 840.dp

private val TreeSidebarWidth = 280.dp
private val RailSidebarWidth = 92.dp

private val StatusBarHeight = 28.dp

@Composable
fun MainWindow(isNavigationVisible: Boolean) {
    val services = remember { ModuleRegistry.services }
    val uiByService = remember { ModuleRegistry.uis.associateBy { it.serviceId } }

    var selection by remember {
        mutableStateOf(services.firstOrNull()?.let { NavSelection(it.id) })
    }

    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= WideSidebarThreshold
            Row(modifier = Modifier.fillMaxSize()) {
                if (isNavigationVisible && services.isNotEmpty()) {
                    val sidebarModifier =
                        Modifier
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                    if (wide) {
                        ModuleTree(
                            services = services,
                            selection = selection,
                            onSelect = { selection = it },
                            modifier = sidebarModifier.width(TreeSidebarWidth),
                        )
                    } else {
                        ModuleRail(
                            services = services,
                            selection = selection,
                            onSelect = { selection = it },
                            modifier = sidebarModifier.width(RailSidebarWidth),
                        )
                    }
                    VerticalDivider()
                }
                // Content area: renders the module's own UI, or the placeholder
                // workspace when an object type (queue/listener/topic) is picked.
                Box(modifier = Modifier.weight(1f).fillMaxSize().padding(16.dp)) {
                    val current = selection
                    when {
                        current == null -> {
                            Text(
                                "Select a module from the sidebar",
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }

                        current.objectType == null -> {
                            val ui = uiByService[current.serviceId]
                            if (ui != null) {
                                ui.content()
                            } else {
                                Text(
                                    "Module UI missing for ${current.serviceId}",
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                        }

                        else -> {
                            val service = services.firstOrNull { it.id == current.serviceId }
                            ObjectTypeWorkspace(
                                serviceName = service?.name ?: current.serviceId,
                                objectType = current.objectType,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        StatusBar(currentModuleName = selection?.let { sel -> services.firstOrNull { it.id == sel.serviceId }?.name })
    }
}

/**
 * Placeholder workspace for a connection's object type
 * (queue / listener / topic) until the real object views land.
 */
@Composable
private fun ObjectTypeWorkspace(
    serviceName: String,
    objectType: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "$serviceName / $objectType",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Objects of this type will be listed here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
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
