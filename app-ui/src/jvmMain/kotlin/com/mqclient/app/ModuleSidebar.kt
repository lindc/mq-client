package com.mqclient.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mqclient.core.BusinessService

/**
 * What a tree entry points at. `objectType == null` selects the module's own
 * page (its [com.mqclient.core.BusinessUi] contribution); a non-null type
 * selects the per-connection object workspace of that kind.
 */
data class NavSelection(val serviceId: String, val objectType: String? = null)

/** Object kinds offered under every connection — the tree's leaf level. */
private val ObjectTypes = listOf("queue", "listener", "topic")

private val Level1RowHeight = 36.dp
private val Level2RowHeight = 32.dp

/**
 * Wide left sidebar: one row per connection (level 1), expandable into its
 * object types (level 2). Level-2 rows draw a dashed branch that hangs off
 * the parent label column, so the hierarchy reads at a glance.
 */
@Composable
fun ModuleTree(
    services: List<BusinessService>,
    selection: NavSelection?,
    onSelect: (NavSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    // All connections start expanded so the two-level structure is visible
    // without any interaction.
    var expandedIds by remember { mutableStateOf(services.map { it.id }.toSet()) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        services.forEach { svc ->
            val expanded = svc.id in expandedIds
            SidebarRow(
                height = Level1RowHeight,
                selected = selection == NavSelection(svc.id),
                onClick = {
                    onSelect(NavSelection(svc.id))
                    expandedIds =
                        if (expanded) expandedIds - svc.id else expandedIds + svc.id
                },
                leading = {
                    Text(
                        text = if (expanded) "▾" else "▸",
                        fontSize = 12.sp,
                        modifier = Modifier.width(14.dp),
                        textAlign = TextAlign.Center,
                    )
                },
                label = {
                    Text(
                        text = svc.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )

            if (expanded) {
                ObjectTypes.forEachIndexed { index, type ->
                    val isLast = index == ObjectTypes.lastIndex
                    SidebarRow(
                        height = Level2RowHeight,
                        selected = selection == NavSelection(svc.id, type),
                        onClick = { onSelect(NavSelection(svc.id, type)) },
                        leading = {
                            BranchLine(color = MaterialTheme.colorScheme.outlineVariant, hasSiblingBelow = !isLast)
                        },
                        label = {
                            Text(
                                text = type,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * Collapsed navigation for narrow windows: one pill per connection. Clicking
 * a pill selects the module page and opens a menu listing the module page
 * again plus that connection's object types.
 */
@Composable
fun ModuleRail(
    services: List<BusinessService>,
    selection: NavSelection?,
    onSelect: (NavSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuForId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
    ) {
        services.forEach { svc ->
            val active = selection?.serviceId == svc.id
            val interactionSource = remember { MutableInteractionSource() }
            val hovered by interactionSource.collectIsHoveredAsState()
            val pillColor =
                when {
                    active -> MaterialTheme.colorScheme.secondaryContainer
                    hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
                    else -> Color.Transparent
                }

            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(pillColor)
                            .clickable(interactionSource = interactionSource, indication = null) {
                                onSelect(NavSelection(svc.id))
                                menuForId = svc.id
                            }
                            .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = svc.name,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        color =
                            if (active) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DropdownMenu(
                    expanded = menuForId == svc.id,
                    onDismissRequest = { menuForId = null },
                ) {
                    val moduleSelected = selection?.serviceId == svc.id && selection.objectType == null
                    DropdownMenuItem(
                        text = {
                            Text(svc.name, fontWeight = if (moduleSelected) FontWeight.SemiBold else FontWeight.Normal)
                        },
                        onClick = {
                            menuForId = null
                            onSelect(NavSelection(svc.id))
                        },
                    )
                    HorizontalDivider()
                    ObjectTypes.forEach { type ->
                        val selected = selection == NavSelection(svc.id, type)
                        DropdownMenuItem(
                            text = {
                                Text(type, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            },
                            onClick = {
                                menuForId = null
                                onSelect(NavSelection(svc.id, type))
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One selectable sidebar row: full-width rounded highlight, hover feedback,
 * and [leading] + [label] content laid out left to right.
 */
@Composable
private fun SidebarRow(
    height: Dp,
    selected: Boolean,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    label: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val rowColor =
        when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(6.dp))
                .background(rowColor)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(start = 6.dp, end = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.width(6.dp))
            Box(modifier = Modifier.weight(1f)) { label() }
        }
    }
}

/**
 * Dashed connector drawn in front of a level-2 row: a vertical dash coming
 * down from the parent's label column that continues into the following
 * siblings — or stops at half height on the last one — plus a horizontal
 * dash reaching toward the label.
 *
 * Geometry: rows start at x = 6dp (row padding), the branch hangs at
 * x = 20dp absolute, which sits right under the level-1 label (which starts
 * at 6 + 14 + 6 = 26dp… close enough that the line reads as attached to the
 * parent row).
 */
@Composable
private fun BranchLine(color: Color, hasSiblingBelow: Boolean) {
    Canvas(modifier = Modifier.width(30.dp).fillMaxHeight()) {
        val halfY = size.height / 2f
        val branchX = 14.dp.toPx()
        val endY = if (hasSiblingBelow) size.height else halfY
        drawLine(color, Offset(branchX, 0f), Offset(branchX, endY), strokeWidth = 1.dp.toPx(), pathEffect = dashed)
        drawLine(color, Offset(branchX, halfY), Offset(size.width, halfY), strokeWidth = 1.dp.toPx(), pathEffect = dashed)
    }
}

private val dashed = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
