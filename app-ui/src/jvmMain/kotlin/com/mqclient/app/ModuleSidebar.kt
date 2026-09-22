package com.mqclient.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import com.mqclient.app.connection.ConnectionContextMenu
import com.mqclient.app.connection.ContextMenuBox
import com.mqclient.core.ConnectionActions
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ModuleRegistry

/**
 * What a tree entry points at. `objectType == null` selects the connection's
 * overview (its [com.mqclient.core.AdapterUI] contribution); a non-null type
 * selects the object workspace of that kind.
 */
data class NavSelection(val connectionId: String, val objectType: String? = null)

/** Object kinds offered under every connection — the tree's leaf level. */
val ObjectTypes = listOf("queue", "listener", "topic")

private val ConnectionRowHeight = 52.dp
private val ObjectRowHeight = 32.dp

/**
 * Wide left sidebar: one row per connection, expandable into its object types.
 * A connection row reads left to right as: status dot, expand arrow, middleware
 * icon, then the name (with the MQ endpoint name in brackets) over the
 * `host:port` endpoint, with the connection state on the right.
 *
 * Every connection starts collapsed. A connection row is right-clickable:
 * [actions] + the row's `ServiceAdapter` (SPI) produce the context menu items,
 * rendered by [ConnectionContextMenu] — the sidebar knows nothing about them.
 *
 * @param propertiesServiceIds 注册了 [com.mqclient.core.ConnectionPropertiesUi]
 *   的模块 id 集合：没有属性面板的模块，菜单里不显示「查看属性」项。
 */
@Composable
fun ModuleTree(
    connections: List<ConnectionInfo>,
    selection: NavSelection?,
    onSelect: (NavSelection) -> Unit,
    actions: ConnectionActions,
    propertiesServiceIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }
    val adaptersById = remember { ModuleRegistry.services.associateBy { it.id } }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        connections.forEach { connection ->
            val expanded = connection.id in expandedIds
            // 菜单的开关与光标位置由每行的 ContextMenuBox 自管，这里只把渲染槽
            // （含该行的 adapter）递进去；没有 adapter 的连接右键无菜单。
            val adapter = adaptersById[connection.serviceId]
            val contextMenu: ( @[Composable] (DpOffset, () -> Unit) -> Unit )? =
                adapter?.let { svc ->
                    @Composable { menuOffset: DpOffset, onDismiss: () -> Unit ->
                        ConnectionContextMenu(
                            connection = connection,
                            adapter = svc,
                            actions = actions,
                            hasPropertiesUi = connection.serviceId in propertiesServiceIds,
                            menuOffset = menuOffset,
                            onDismiss = onDismiss,
                        )
                    }
                }
            ConnectionRow(
                connection = connection,
                expanded = expanded,
                selected = selection == NavSelection(connection.id),
                onClick = {
                    onSelect(NavSelection(connection.id))
                    expandedIds =
                        if (expanded) expandedIds - connection.id else expandedIds + connection.id
                },
                contextMenu = contextMenu,
            )

            if (expanded) {
                ObjectTypes.forEachIndexed { index, type ->
                    ObjectTypeRow(
                        type = type,
                        isLast = index == ObjectTypes.lastIndex,
                        selected = selection == NavSelection(connection.id, type),
                        onClick = { onSelect(NavSelection(connection.id, type)) },
                    )
                }
            }
        }
    }
}

/**
 * One connection row: [status dot] [arrow] [middleware icon] [name [endpoint
 * name] / host:port] [state label].
 *
 * The row is wrapped in [ContextMenuBox] so a secondary (right) click opens
 * [contextMenu] anchored to the row. Right-clicking does NOT change selection.
 */
@Composable
private fun ConnectionRow(
    connection: ConnectionInfo,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    contextMenu: ( @[Composable] (DpOffset, () -> Unit) -> Unit )? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val rowColor =
        when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        }

    ContextMenuBox(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(ConnectionRowHeight)
                    .clip(RoundedCornerShape(6.dp))
                    .background(rowColor)
                    .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                    .padding(start = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(connection.status)
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (expanded) "▾" else "▸",
                fontSize = 12.sp,
                modifier = Modifier.width(14.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = middlewareIcon(connection.serviceId),
                contentDescription = null,
                // The artwork carries the brand colours; tinting would flatten it.
                tint = Color.Unspecified,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = connection.name,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "[${connection.mqName}]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(
                    text = connection.endpoint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = connection.status.label,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor(connection.status),
            )
        }
        },
        menu = contextMenu ?: { _, _ -> },
    )
}

/** Object-type leaf: dashed branch and the type name. */
@Composable
private fun ObjectTypeRow(
    type: String,
    isLast: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val rowColor =
        when {
            selected -> MaterialTheme.colorScheme.secondaryContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(ObjectRowHeight)
                .clip(RoundedCornerShape(6.dp))
                .background(rowColor)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BranchLine(color = MaterialTheme.colorScheme.outlineVariant, hasSiblingBelow = !isLast)
        Text(
            text = type,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 状态色：绿 / 黄 / 红 / 灰，状态点 [StatusDot] 和状态文字共用同一份。
 *
 * 刻意不从 [MaterialTheme.colorScheme] 取 —— 语义色里没有「成功 / 警告」，而
 * `primary` / `error` 会被用户在设置里挑的种子色带跑，状态就没法一眼认了。
 *
 * 但固定色值必须按底色的明暗挑亮度档：浅底用 700 档、深底用 400 档。原来写死
 * `0xFF15803D`，深色模式下它就是一块看不见的黑绿。判据取**实际生效的背景亮度**
 * 而不是 `isSystemInDarkTheme()` —— 用户在设置里强制深色、而系统是浅色时，后者
 * 会判反。
 */
@Composable
private fun statusColor(status: ConnectionStatus): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (status) {
        ConnectionStatus.Connected -> if (dark) Color(0xFF4ADE80) else Color(0xFF15803D)
        ConnectionStatus.Connecting -> if (dark) Color(0xFFFACC15) else Color(0xFFA16207)
        ConnectionStatus.Failed -> if (dark) Color(0xFFF87171) else Color(0xFFB91C1C)
        ConnectionStatus.Disconnected -> if (dark) Color(0xFF9AA0A6) else Color(0xFF6B7280)
    }
}

/**
 * 状态点：绿 connected、黄 connecting、红 failed、disconnected 画成空心圈。
 *
 * 空心而不是白底 + 灰边：白点在浅底上需要描边才看得见（原来那圈 `0xFF9AA0A6` 就是
 * 为它加的），换成透明底 + 同色描边之后两种主题下都成立。
 */
@Composable
private fun StatusDot(status: ConnectionStatus) {
    val color = statusColor(status)
    val hollow = status == ConnectionStatus.Disconnected
    Box(
        modifier =
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (hollow) Color.Transparent else color)
                .then(if (hollow) Modifier.border(1.dp, color, CircleShape) else Modifier),
    )
}

/**
 * Collapsed navigation for narrow windows: one pill per connection. Clicking
 * a pill opens the connection and shows a menu with its overview plus the
 * object types and their counts.
 */
@Composable
fun ModuleRail(
    connections: List<ConnectionInfo>,
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
        connections.forEach { connection ->
            val active = selection?.connectionId == connection.id
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
                                onSelect(NavSelection(connection.id))
                                menuForId = connection.id
                            }
                            .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(connection.status)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = connection.name,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color =
                                if (active) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                DropdownMenu(
                    expanded = menuForId == connection.id,
                    onDismissRequest = { menuForId = null },
                ) {
                    val overviewSelected =
                        selection?.connectionId == connection.id && selection.objectType == null
                    DropdownMenuItem(
                        text = {
                            Text(
                                connection.name,
                                color =
                                    if (overviewSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        onClick = {
                            menuForId = null
                            onSelect(NavSelection(connection.id))
                        },
                    )
                    HorizontalDivider()
                    ObjectTypes.forEach { type ->
                        val selected = selection == NavSelection(connection.id, type)
                        DropdownMenuItem(
                            text = {
                                Text(
                                    type,
                                    color =
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = {
                                menuForId = null
                                onSelect(NavSelection(connection.id, type))
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dashed connector drawn in front of a level-2 row: a vertical dash coming
 * down from the parent's label column that continues into the following
 * siblings — or stops at half height on the last one — plus a horizontal
 * dash reaching toward the label.
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
