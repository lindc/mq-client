package com.mqclient.ibmmq.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.pcf.isSystemObject
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogHost
import com.mqclient.ibmmq.ui.properties.rememberObjectPropertiesDialogController
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.mqui.objectlist.ObjectListPanel
import com.mqclient.mqui.state.LoadStateContent
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.mqui.common.rememberDebouncedValue

/**
 * 「简单对象」的声明式配置：这类对象没有队列深度 / 运行状态 / 启停这类强类型语义，
 * 列表页与卡片完全同构（此前是 21 份逐字复制文件，2026-10 收拢于此）。
 * 差异只有四样：对象类型、卡片类型图标、动作 id 前缀、i18n key 前缀。
 */
data class SimpleMqObjectSpec(
    val objectType: MqObjectType,
    /** 卡片第一行的类型图标。 */
    val typeIcon: ImageVector,
    /** 卡片动作 id 前缀（如 "authInfo.properties" 的 authInfo）。 */
    val actionIdPrefix: String,
    /** i18n key 前缀（`{prefix}_load_failed` / `_loading` / `_action_properties` …）。 */
    val i18nPrefix: String,
    /** 是否在属性按钮前追加「在拓扑图中查看」（订阅 / 主题是拓扑节点来源）。 */
    val showTopologyAction: Boolean = false,
)

/** 七类简单对象的配置实例（IbmMqUI 按 Tab 选择）。 */
val AuthInfoSpec = SimpleMqObjectSpec(MqObjectType.AUTH_INFO, Icons.Default.Lock, "authInfo", "auth_info")
val CommInfoSpec = SimpleMqObjectSpec(MqObjectType.COMM_INFO, Icons.Default.SettingsInputAntenna, "commInfo", "comm_info")
val NamelistSpec = SimpleMqObjectSpec(MqObjectType.NAMELIST, Icons.Default.List, "namelist", "namelist")
val ProcessSpec = SimpleMqObjectSpec(MqObjectType.PROCESS, Icons.Default.AccountTree, "process", "process_definition")
val ServiceSpec = SimpleMqObjectSpec(MqObjectType.SERVICE, Icons.Default.Settings, "service", "service")
val SubscriptionSpec = SimpleMqObjectSpec(MqObjectType.SUBSCRIPTION, Icons.Default.Bookmark, "subscription", "subscription", showTopologyAction = true)
val TopicSpec = SimpleMqObjectSpec(MqObjectType.TOPIC, Icons.Default.Forum, "topic", "topic", showTopologyAction = true)

/**
 * 快照 → [ObjectCardData] 的通用适配（合并原 7 份 `toXxxCardData`）：简单对象无
 * 队列深度 / 运行状态这类强类型字段，仅用 [spec] 的类型图标 + 描述作为次级文案，
 * 操作按钮由调用方经 [actions] 注入。
 */
internal fun MqObjectSnapshot.toSimpleCardData(
    spec: SimpleMqObjectSpec,
    actions: List<CardAction> = emptyList(),
): ObjectCardData = ObjectCardData(
    id = name,
    title = name,
    typeIcon = spec.typeIcon,
    statusIcon = null,
    statusText = description,
    metricValue = null,
    actions = actions,
)

/**
 * 简单对象卡片的操作按钮（合并原 7 份 `XxxCardActions`）：当前仅 **属性** 一个按钮
 * （位于尾位，与各对象类型的尾按钮位置一致）。
 */
internal object SimpleObjectCardActions {
    fun create(
        spec: SimpleMqObjectSpec,
        onOpenProperties: () -> Unit,
    ): List<CardAction> = listOf(
        CardAction(
            id = "${spec.actionIdPrefix}.properties",
            icon = Icons.Default.Info,
            tooltip = "${spec.i18nPrefix}_action_properties",
            onClick = onOpenProperties,
        ),
    )
}

/**
 * 「简单对象」列表页的**通用实现**（认证信息 / 通信信息 / 名称列表 / 进程定义 /
 * 服务 / 订阅 / 主题七类共用，此前为 7 份 96–98 行的逐字复制面板）：复用
 * [rememberMqObjectListState] 统一加载状态机 + 通用 [ObjectListToolbar] +
 * [ObjectListPanel]，与所有对象类型列表页同一套加载语义。
 *
 * 无队列深度阈值；系统对象（SYSTEM.*）默认隐藏，「显示系统」开关切换。
 * 拓扑查看动作在最前（[SimpleMqObjectSpec.showTopologyAction]），属性按钮恒在最后。
 */
@Composable
fun SimpleMqObjectListPanel(
    connectionId: String,
    spec: SimpleMqObjectSpec,
    modifier: Modifier = Modifier,
    onOpenProperties: (String) -> Unit = {},
) {
    var searchText by remember { mutableStateOf("") }
    val appliedSearch = rememberDebouncedValue(searchText)
    var showSystem by remember { mutableStateOf(false) }
    // 对象属性对话框（双击卡片 / 属性按钮打开；统一入口持有状态与渲染）。
    val propertiesController = rememberObjectPropertiesDialogController(connectionId)
    ObjectPropertiesDialogHost(propertiesController)

    val state = rememberMqObjectListState(
        connectionId = connectionId,
        objectType = spec.objectType,
        loadFailedText = { e ->
            stringResource("${spec.i18nPrefix}_load_failed").withArg(e.message ?: e::class.simpleName ?: "")
        },
        scanFailedText = { e ->
            stringResource("${spec.i18nPrefix}_scan_failed").withArg(e.message ?: e::class.simpleName ?: "")
        },
    )

    LoadStateContent(
        state = state.loadState,
        onRetry = state.retry,
        loadingMessage = stringResource("${spec.i18nPrefix}_loading"),
        modifier = Modifier.fillMaxSize(),
        // 空列表的呈现交给 ObjectListPanel（其内部自带空态 + 工具栏），故此处恒返回 false。
        emptyCheck = { false },
        content = { objects ->
            val filtered = objects
                .filter { it.name.contains(appliedSearch, ignoreCase = true) }
                .filter { showSystem || !isSystemObject(it.name) }
            ObjectListPanel(
                items = filtered.map { obj ->
                    // 拓扑查看在最前，属性按钮恒在最后（各对象类型尾按钮一致）。
                    val actions = buildList {
                        if (spec.showTopologyAction) {
                            add(topologyViewAction(connectionId, obj.name))
                        }
                        addAll(
                            SimpleObjectCardActions.create(
                                spec,
                                onOpenProperties = {
                                    propertiesController.open(spec.objectType, obj.name)
                                },
                            ),
                        )
                    }
                    obj.toSimpleCardData(spec = spec, actions = actions)
                },
                onCardClick = { card -> onOpenProperties(card.id) },
                onCardDoubleClick = { card ->
                    propertiesController.open(spec.objectType, card.id)
                },
                toolbarContent = {
                    ObjectListToolbar(
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        onRefresh = state.refresh,
                        refreshing = state.isRefreshing,
                        placeholder = stringResource("${spec.i18nPrefix}_search_placeholder"),
                        refreshText = stringResource("${spec.i18nPrefix}_refresh"),
                        showSystemToggle = true,
                        showSystem = showSystem,
                        onShowSystemChange = { showSystem = !showSystem },
                    )
                },
                loading = false,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}
