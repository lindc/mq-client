package com.mqclient.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mqclient.app.connection.MiddlewareTypeCard
import com.mqclient.app.connection.toMiddlewareType
import com.mqclient.core.ModuleRegistry
import com.mqclient.core.ServiceAdapter
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.stringResource

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
    // 第二步要创建的服务类型；由第一步的卡片点击直接决定。
    var selectedServiceId by remember { mutableStateOf(grouped[selectedCategory]?.firstOrNull()?.id) }
    var step by remember { mutableStateOf(NewConnectionStep.SELECT) }

    // 第二步的面板状态：key 到选中类型上，回到第一步再进来时草稿重置。
    val selectedForm =
        if (step == NewConnectionStep.CREATE) {
            ModuleRegistry.forms.firstOrNull { it.serviceId == selectedServiceId }
        } else {
            null
        }
    val formState = selectedForm?.let { form ->
        key(form.serviceId) { form.rememberFormState(defaultName) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(780.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HeaderBar(onClose = onDismiss)
                HorizontalDivider()
                when (step) {
                    NewConnectionStep.SELECT -> SelectStepBody(
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
                            selectedServiceId = adapter.id
                            step = NewConnectionStep.CREATE
                        },
                    )
                    NewConnectionStep.CREATE -> {
                        if (formState != null) {
                            // 尺寸与滚动归面板自己（ConnectionFormUi 契约的一部分）。
                            formState.Content()
                        } else {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 260.dp, max = 460.dp)
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
                    }
                }
                HorizontalDivider()
                DialogFooter(
                    step = step,
                    hasForm = formState != null,
                    onClose = onDismiss,
                    onBack = { step = NewConnectionStep.SELECT },
                    onFinish = {
                        // false = 失败，错误已由模块面板展示 —— 保持打开，什么都不做。
                        if (formState?.submit() == true) {
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
                .heightIn(min = 260.dp, max = 460.dp),
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
