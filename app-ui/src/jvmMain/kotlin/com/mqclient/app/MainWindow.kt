package com.mqclient.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.core.ModuleRegistry

/**
 * Window widths at or above this get the full tree sidebar; narrower windows
 * collapse the navigation to the [ModuleRail] pill column.
 */
private val WideSidebarThreshold = 840.dp

private val TreeSidebarWidth = 280.dp
private val RailSidebarWidth = 92.dp

private val StatusBarHeight = 28.dp

private val TabStripHeight = 40.dp
private val TabHeight = 28.dp
private val TabMaxWidth = 180.dp

@Composable
fun MainWindow(
    isNavigationVisible: Boolean,
    selection: NavSelection?,
    onSelect: (NavSelection) -> Unit,
) {
    val services = remember { ModuleRegistry.services }
    val uiByService = remember { ModuleRegistry.uis.associateBy { it.serviceId } }

    // Level-2 rows (queue/listener/topic) each get their own closable tab;
    // level-1 rows keep showing the module's page directly.
    var openTabs by remember { mutableStateOf(listOf<NavSelection>()) }
    var activeTab by remember { mutableStateOf<NavSelection?>(null) }

    val serviceName = { sel: NavSelection ->
        services.firstOrNull { it.id == sel.serviceId }?.name ?: sel.serviceId
    }

    fun openTab(tab: NavSelection) {
        if (tab !in openTabs) openTabs = openTabs + tab
        activeTab = tab
    }

    fun closeTab(tab: NavSelection) {
        val index = openTabs.indexOf(tab)
        openTabs = openTabs - tab
        if (activeTab != tab) return
        // Fall back to the neighbour on the right, then to the module page.
        val next = openTabs.getOrNull(index) ?: openTabs.lastOrNull()
        activeTab = next
        onSelect(next ?: NavSelection(tab.serviceId))
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
                    val onNavSelect: (NavSelection) -> Unit = {
                        onSelect(it)
                        if (it.objectType != null) openTab(it) else activeTab = null
                    }
                    if (wide) {
                        ModuleTree(
                            services = services,
                            selection = selection,
                            onSelect = onNavSelect,
                            modifier = sidebarModifier.width(TreeSidebarWidth),
                        )
                    } else {
                        ModuleRail(
                            services = services,
                            selection = selection,
                            onSelect = onNavSelect,
                            modifier = sidebarModifier.width(RailSidebarWidth),
                        )
                    }
                    VerticalDivider()
                }
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    if (openTabs.isNotEmpty()) {
                        ObjectTabStrip(
                            tabs = openTabs,
                            active = activeTab,
                            tabTitle = { "${serviceName(it)} / ${it.objectType}" },
                            onSelect = {
                                activeTab = it
                                onSelect(it)
                            },
                            onClose = ::closeTab,
                        )
                    }
                    // Content area: renders the module's own UI, or the placeholder
                    // workspace when an object type (queue/listener/topic) is picked.
                    Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
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
                                ObjectTypeWorkspace(
                                    serviceName = serviceName(current),
                                    objectType = current.objectType,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
        StatusBar(currentModuleName = selection?.let { sel -> services.firstOrNull { it.id == sel.serviceId }?.name })
    }
}

/**
 * Tab strip above the content area: one closable tab per opened object type.
 * Tabs are a plain scrolling row rather than M3's `TabRow`, which has no
 * affordance for a close button.
 */
@Composable
private fun ObjectTabStrip(
    tabs: List<NavSelection>,
    active: NavSelection?,
    tabTitle: (NavSelection) -> String,
    onSelect: (NavSelection) -> Unit,
    onClose: (NavSelection) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(TabStripHeight)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                ObjectTab(
                    title = tabTitle(tab),
                    active = tab == active,
                    onClick = { onSelect(tab) },
                    onClose = { onClose(tab) },
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ObjectTab(
    title: String,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val containerColor =
        when {
            active -> MaterialTheme.colorScheme.secondaryContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        }

    Row(
        modifier =
            Modifier
                .height(TabHeight)
                .widthIn(max = TabMaxWidth)
                .clip(RoundedCornerShape(6.dp))
                .background(containerColor)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(4.dp))
        CloseButton(onClick = onClose)
    }
}

/** Small round "×" that closes its tab; sized to stay inside the tab's height. */
@Composable
private fun CloseButton(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Box(
        modifier =
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(
                    if (hovered) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                )
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "×",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
