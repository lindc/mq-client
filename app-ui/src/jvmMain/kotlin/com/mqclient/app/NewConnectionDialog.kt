package com.mqclient.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import com.mqclient.app.connection.MiddlewareTypeCard
import com.mqclient.app.connection.toMiddlewareType
import com.mqclient.core.ConnectionFormState
import com.mqclient.core.ModuleRegistry
import com.mqclient.core.ServiceAdapter
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.stringResource
import dev.nucleusframework.application.DecoratedDialog
import dev.nucleusframework.application.LocalNucleusWindow

/** 对话框的两步流程：先选服务类型，再进对应模块的创建面板。 */
private enum class NewConnectionStep { SELECT, CREATE }

/**
 * 新建连接对话框（向导两步式）：
 *
 *  - **第一步**：左侧类目导航 + 服务类型卡片网格（每行 2 列），点卡片直接进第二步。
 *    类目 tab **完全由 SPI 扫描**得出 —— [ModuleRegistry.services] 按 category 分组
 *    （[ModuleRegistry.groupByCategory]），新模块带新 category 时自动出现，这里没有
 *    任何硬编码的类目清单；
 *  - **第二步**：**面板完全归模块** —— 宿主按选中的 [ServiceAdapter.id] 找
 *    [ModuleRegistry.forms] 里的 [com.mqclient.core.ConnectionFormUi]（SPI 注册，
 *    如 openai-ui 的 `OpenAiConnectionForm`），只渲染 [com.mqclient.core.ConnectionFormState.Content]；
 *    字段、校验、错误展示、落盘都是模块自己的事。没有注册表单的类型显示占位提示。
 *
 * 「完成」是宿主 footer 触发 [com.mqclient.core.ConnectionFormState.submit] 的入口：
 *  - 返回 `true` = 模块已落盘 → 宿主经 [onConnectionCreated] 通知调用方（从磁盘重载
 *    内存态）并关窗；
 *  - 返回 `false` = 失败，错误已由模块面板自己展示 —— **宿主什么都不做，对话框保持
 *    打开**。错误从不出面板边界，宿主只认布尔值。
 *
 * 窗口用可缩放的 DecoratedDialog：页面填满窗口剩余空间，没有最大尺寸限制——
 * 用户拖多大页面就多大，唯一的下限是窗口最小尺寸（`setMinimumSize`）。
 */
@Composable
internal fun NewConnectionDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConnectionCreated: () -> Unit,
) {
    val grouped = remember { ModuleRegistry.groupByCategory(ModuleRegistry.services) }
    val categoryOrder = remember(grouped) { grouped.keys.sorted() }
    var selectedCategory by remember { mutableStateOf(categoryOrder.firstOrNull().orEmpty()) }
    // 第二步要创建的服务类型：点卡片时设置；「上一步」保留（回到第二页草稿还在）。
    var selectedServiceId by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(NewConnectionStep.SELECT) }

    // 所有已注册表单的状态在对话框打开时**一次性组合好**（key 到各自 serviceId 上，
    // 隐藏不算销毁）：点卡片切换面板 = 纯「放置」切换，没有任何组合/销毁发生 ——
    // 秒切，也不会闪现旧表单。表单少，这次性的前置组合成本可以忽略。
    val formStates = mutableMapOf<String, ConnectionFormState>()
    ModuleRegistry.forms.forEach { form ->
        key(form.serviceId) { formStates[form.serviceId] = form.rememberFormState(defaultName) }
    }

    // 可缩放的真实窗口（Compose Dialog 不支持用户拖拽缩放）：初始 780×560，
    // 用户自由调整大小 —— 页面填满窗口、只保留窗口最小尺寸限制（见 setMinimumSize）。
    DecoratedDialog(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = DpSize(780.dp, 560.dp)),
        title = stringResource("menu_new_connection"),
        resizable = true,
    ) {
        val nucleusWindow = LocalNucleusWindow.current
        LaunchedEffect(nucleusWindow) { nucleusWindow.setMinimumSize(DpSize(720.dp, 480.dp)) }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HeaderBar(onClose = onDismiss)
                HorizontalDivider()
                // 两页都组合在同一容器里：容器占窗口剩余高度，只放置当前页 ——
                // 两个 page 永远一样大（= 窗口剩余空间），窗口拉伸时一起变大。
                SameSizePageContainer(
                    showSecond = step == NewConnectionStep.CREATE,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    first = {
                        SelectStepBody(
                            grouped = grouped,
                            categoryOrder = categoryOrder,
                            selectedCategory = selectedCategory,
                            onSelectCategory = { category ->
                                selectedCategory = category
                                val ids = grouped[category].orEmpty()
                                if (ids.none { it.id == selectedServiceId }) {
                                    selectedServiceId = ids.firstOrNull()?.id
                                }
                            },
                        onSelectType = { adapter ->
                            // 每次进入创建页都从空白开始（「新建连接」的语义）；
                            // reset 是纯状态赋值，不损失秒切（面板常驻、只改值）。
                            formStates[adapter.id]?.reset(defaultName)
                            selectedServiceId = adapter.id
                            step = NewConnectionStep.CREATE
                        },
                        )
                    },
                    second = {
                        val formState = selectedServiceId?.let { formStates[it] }
                        if (formState != null) {
                            // 尺寸归面板自己（ConnectionFormUi 契约）：填满容器、超出滚动。
                            formState.Content()
                        } else {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(20.dp),
                            ) {
                                Text(
                                    text = stringResource("new_connection_form_todo"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                )
                HorizontalDivider()
                DialogFooter(
                    step = step,
                    hasForm = selectedServiceId != null && formStates.containsKey(selectedServiceId),
                    onClose = onDismiss,
                    // 草稿保留：回第一页再进来，面板状态原样（秒切的一部分）。
                    onBack = { step = NewConnectionStep.SELECT },
                    onFinish = {
                        // false = 失败，错误已由模块面板展示 —— 保持打开，什么都不做。
                        if (selectedServiceId != null && formStates[selectedServiceId]?.submit() == true) {
                            onConnectionCreated()
                            onDismiss()
                        }
                    },
                )
            }
        }
    }
}

/** 标题栏：标题文字走 i18n（`menu_new_connection`），右侧 ✕ 关闭。 */
@Composable
private fun HeaderBar(onClose: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource("menu_new_connection"),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        PressableButton(
            onClick = onClose,
            standardMinWidth = false, // ✕ 是图标按钮，不套「4 字宽」标准。
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) { Text("✕") }
    }
}

/**
 * 第一步：左侧类目导航 + 服务类型卡片网格（每行 2 列）。
 *
 * 内容区 `spacedBy(12.dp, CenterVertically)`：卡片行之间保持 12dp 间距，同时内容
 * 不满一屏时整体垂直居中（`heightIn(min = 260.dp)` 给了它可分配的富余高度）；内容
 * 超出 `max = 460.dp` 时切到内部滚动。
 *
 * 网格用 `chunked(2)` 而不是 LazyVerticalGrid：类目下卡片最多几张，lazy 布局会
 * 强制填满可用高度，「内容少时收缩 + 垂直居中」都做不了；普通 Row 组合 wrap 内容，
 * 两个目标都满足。
 */
@Composable
private fun SelectStepBody(
    grouped: Map<String, List<ServiceAdapter>>,
    categoryOrder: List<String>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    onSelectType: (ServiceAdapter) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
    ) {
        CategorySideNav(
            tabs = categoryOrder,
            selected = selectedCategory,
            onSelect = onSelectCategory,
            modifier = Modifier.fillMaxHeight().widthIn(min = 110.dp, max = 140.dp),
        )
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource("new_connection_pick_type"),
                style = MaterialTheme.typography.titleMedium,
            )
            grouped[selectedCategory].orEmpty().chunked(2).forEach { row ->
                // IntrinsicSize.Min + 卡片 fillMaxHeight：同一行两张卡等高。
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { adapter ->
                        MiddlewareTypeCard(
                            type = adapter.toMiddlewareType(),
                            onClick = { onSelectType(adapter) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    // 奇数行补一个占位，让单卡保持半宽、与上一行对齐。
                    if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 左侧类目导航：与设置对话框的 SideNav 同一套路 —— 整行可点、选中行
 * `primaryContainer` 底。手搓而不是 M3 组件，理由同设置侧栏。
 */
@Composable
private fun CategorySideNav(
    tabs: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp),
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            Text(
                text = tab,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .clickable { onSelect(tab) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * 共享 footer：第一步只有「取消」（点卡片即进第二步）；第二步「上一步」+
 * 「完成」—— 完成触发模块面板的 [com.mqclient.core.ConnectionFormState.submit]，
 * 失败时对话框保持打开；没有表单的类型只有「上一步」。
 */
@Composable
private fun DialogFooter(
    step: NewConnectionStep,
    hasForm: Boolean,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (step) {
            NewConnectionStep.SELECT ->
                // 点卡片即进第二步，这里只需要一个出口。
                PressableButton(
                    onClick = onClose,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) { Text(stringResource("common_cancel")) }
            NewConnectionStep.CREATE -> {
                PressableButton(
                    onClick = onBack,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) { Text(stringResource("common_back")) }
                if (hasForm) {
                    Spacer(Modifier.width(8.dp))
                    PressableButton(onClick = onFinish) { Text(stringResource("common_finish")) }
                }
            }
        }
    }
}

/**
 * 「两页同尺寸」的底层容器：两页**都常驻组合**（[first] / [second]），容器尺寸取
 * 两页内容的最大值（自适应），但只**放置**当前页 —— 另一页测量了不放置，因此不绘制、
 * 不参与点击/焦点，切页时对话框尺寸也不跳变。
 *
 * 附带的好处：两页的状态都常驻（第一页的导航展开、第二页的表单草稿在切页时保留）。
 * 用自定义 [Layout] 而不是 `alpha(0f)` 藏页：alpha 只隐藏绘制，隐藏页仍会吃掉
 * 点击和焦点。
 */
@Composable
private fun SameSizePageContainer(
    showSecond: Boolean,
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    Layout(content = { first(); second() }, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val width = placeables.maxOf { it.width }
        val height = placeables.maxOf { it.height }
        layout(width, height) {
            // 只放置当前页；另一页留在测量结果里不参与布局。
            val active = if (showSecond) placeables.getOrNull(1) else placeables.getOrNull(0)
            active?.placeRelative(0, 0)
        }
    }
}
