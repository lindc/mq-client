package com.mqclient.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import com.mqclient.ai.AiAdapterRegistry
import com.mqclient.ai.AiProviders
import com.mqclient.app.connection.MiddlewareTypeCard
import com.mqclient.app.connection.toMiddlewareType
import com.mqclient.core.AiConnection
import com.mqclient.core.AiConnectionRepository
import com.mqclient.core.ModuleRegistry
import com.mqclient.core.ServiceAdapter
import com.mqclient.app.settings.LabeledField
import com.mqclient.app.settings.ModelComboBox
import com.mqclient.app.settings.SettingsDropdownRow
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.stringResource
import dev.nucleusframework.application.DecoratedDialog
import dev.nucleusframework.application.LocalNucleusWindow
import java.util.UUID

/** 对话框的两步流程：先选服务类型，再进对应类型的创建表单。 */
private enum class NewConnectionStep { SELECT, CREATE }

/**
 * 新建连接对话框（向导两步式）：
 *
 *  - **第一步**：左侧类目导航 + 服务类型卡片单选，底部「关闭 / 下一步」。类目 tab
 *    **完全由 SPI 扫描**得出 —— [ModuleRegistry.services] 按 [ServiceAdapter.category]
 *    分组（[ModuleRegistry.groupByCategory]），新模块带新 category 时 tab 自动出现，
 *    这里没有任何硬编码的类目清单；
 *  - **第二步**：所选类型的创建表单。AI（[AiProviders.OPENAI_COMPATIBLE]）是独立的
 *    [AiConnection] 体系（存 `ai.connections`，不经 [com.mqclient.core.ConnectionStore]），
 *    表单落盘走 [AiConnectionRepository.create]（token 由 ConfigManager 的名字匹配自动
 *    加密），成功后经 [onAiCreated] 交调用方同步内存里的 `appConfig` —— 不同步的话，
 *    设置对话框下次整份覆盖保存会把这条新连接抹掉。其余类型（MQ）表单在后续版本提供，
 *    第二步只放提示 + 「上一步」。
 *
 * 表单字段状态提到对话框级：保存按钮在共享 footer 里，两个 step 切换时草稿不丢。
 */
@Composable
internal fun NewConnectionDialog(
    defaultAiName: String,
    onDismiss: () -> Unit,
    onAiCreated: (AiConnection) -> Unit,
) {
    val grouped = remember { ModuleRegistry.groupByCategory(ModuleRegistry.services) }
    val categoryOrder = remember(grouped) { grouped.keys.sorted() }
    var selectedCategory by remember { mutableStateOf(categoryOrder.firstOrNull().orEmpty()) }
    // 第一步选中的服务类型；切类目时落回该类目的第一个类型，保证「下一步」总有目标。
    var selectedServiceId by remember { mutableStateOf(grouped[selectedCategory]?.firstOrNull()?.id) }
    var step by remember { mutableStateOf(NewConnectionStep.SELECT) }

    // AI 表单草稿（对话框级状态，见类注释）。
    val providers = remember { AiAdapterRegistry.providers() }
    var saveError by remember { mutableStateOf<String?>(null) }
    var aiName by remember { mutableStateOf(defaultAiName) }
    var aiProviderId by remember { mutableStateOf(providers.firstOrNull()?.id.orEmpty()) }
    var aiBaseUrl by remember { mutableStateOf(providers.firstOrNull()?.defaultBaseUrl.orEmpty()) }
    var aiToken by remember { mutableStateOf("") }
    var aiTokenVisible by remember { mutableStateOf(false) }
    var aiModel by remember { mutableStateOf(providers.firstOrNull()?.defaultModels?.firstOrNull().orEmpty()) }

    val selectedAdapter =
        grouped[selectedCategory].orEmpty().firstOrNull { it.id == selectedServiceId }
    val selectedIsAi = selectedAdapter?.id == AiProviders.OPENAI_COMPATIBLE.id

    DecoratedDialog(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = DpSize(780.dp, 560.dp)),
        title = stringResource("menu_new_connection"),
        resizable = true,
    ) {
        val nucleusWindow = LocalNucleusWindow.current
        LaunchedEffect(nucleusWindow) { nucleusWindow.setMinimumSize(DpSize(720.dp, 420.dp)) }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                HeaderBar(onClose = onDismiss)
                HorizontalDivider()
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (step) {
                        NewConnectionStep.SELECT -> Row(modifier = Modifier.fillMaxSize()) {
                            CategorySideNav(
                                tabs = categoryOrder,
                                selected = selectedCategory,
                                onSelect = { category ->
                                    selectedCategory = category
                                    val ids = grouped[category].orEmpty()
                                    if (ids.none { it.id == selectedServiceId }) {
                                        selectedServiceId = ids.firstOrNull()?.id
                                    }
                                },
                                modifier = Modifier.fillMaxHeight().widthIn(min = 110.dp, max = 140.dp),
                            )
                            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Column(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .padding(horizontal = 20.dp, vertical = 16.dp),
                            ) {
                                Text(
                                    text = stringResource("new_connection_pick_type"),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(12.dp))
                                // 卡片网格：每行 2 列，间距 12dp；点击卡片直接进入第二步。
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    modifier = Modifier.fillMaxWidth().weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(grouped[selectedCategory].orEmpty(), key = { it.id }) { adapter ->
                                        MiddlewareTypeCard(
                                            type = adapter.toMiddlewareType(),
                                            onClick = {
                                                selectedServiceId = adapter.id
                                                step = NewConnectionStep.CREATE
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        NewConnectionStep.CREATE -> CreateStep(
                            isAi = selectedIsAi,
                            providers = providers,
                            aiName = aiName,
                            aiProviderId = aiProviderId,
                            aiBaseUrl = aiBaseUrl,
                            aiToken = aiToken,
                            aiTokenVisible = aiTokenVisible,
                            aiModel = aiModel,
                            onNameChange = { aiName = it },
                            onProviderChange = { picked ->
                                aiProviderId = picked.id
                                // 切 Provider 时把没填过的默认值补上；用户填过的值不动。
                                if (aiBaseUrl.isBlank()) aiBaseUrl = picked.defaultBaseUrl
                                if (aiModel.isBlank()) {
                                    aiModel = picked.defaultModels.firstOrNull().orEmpty()
                                }
                            },
                            onBaseUrlChange = { aiBaseUrl = it },
                            onTokenChange = { aiToken = it },
                            onTokenVisibleChange = { aiTokenVisible = it },
                            onModelChange = { aiModel = it },
                        )
                    }
                }
                HorizontalDivider()
                DialogFooter(
                    step = step,
                    selectedIsAi = selectedIsAi,
                    saveError = saveError,
                    onClose = onDismiss,
                    onBack = { step = NewConnectionStep.SELECT },
                    onSave = {
                        // 与设置页保存同风格：同步落盘（create 是普通函数，非挂起）。
                        val connection = AiConnection(
                            id = UUID.randomUUID().toString(),
                            name = aiName.ifBlank { defaultAiName },
                            providerId = aiProviderId,
                            baseUrl = aiBaseUrl.trim(),
                            token = aiToken,
                            model = aiModel.trim(),
                        )
                        AiConnectionRepository.create(connection)
                            .onSuccess {
                                onAiCreated(connection)
                                onDismiss()
                            }
                            .onFailure { error ->
                                saveError = error.message ?: error::class.simpleName
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) { Text("✕") }
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
 * 第二步内容：所选类型的创建表单。AI 渲染完整表单（字段状态由调用方持有，
 * 保存按钮在共享 footer）；其余类型骨架期只有提示。
 */
@Composable
private fun CreateStep(
    isAi: Boolean,
    providers: List<com.mqclient.ai.AiProvider>,
    aiName: String,
    aiProviderId: String,
    aiBaseUrl: String,
    aiToken: String,
    aiTokenVisible: Boolean,
    aiModel: String,
    onNameChange: (String) -> Unit,
    onProviderChange: (com.mqclient.ai.AiProvider) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onTokenVisibleChange: (Boolean) -> Unit,
    onModelChange: (String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        if (!isAi) {
            Text(
                text = stringResource("new_connection_form_todo"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LabeledField(stringResource("conn_name_label")) {
            OutlinedTextField(
                value = aiName,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        Spacer(Modifier.height(8.dp))

        SettingsDropdownRow(
            label = stringResource("ai_field_provider"),
            currentLabel =
                providers.firstOrNull { it.id == aiProviderId }?.name ?: aiProviderId.ifBlank { "—" },
            options = providers,
            optionLabel = { it.name },
            onPick = onProviderChange,
        )
        Spacer(Modifier.height(8.dp))

        LabeledField(stringResource("ai_field_url")) {
            OutlinedTextField(
                value = aiBaseUrl,
                onValueChange = onBaseUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(providers.firstOrNull { it.id == aiProviderId }?.defaultBaseUrl.orEmpty())
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        LabeledField(stringResource("ai_field_api_key")) {
            OutlinedTextField(
                value = aiToken,
                onValueChange = onTokenChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation =
                    if (aiTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                // 空字段也给个圆点提示「这里是密钥」；一开始输入就消失。
                placeholder = { Text("••••••••") },
                trailingIcon = {
                    Box(
                        modifier =
                            Modifier
                                .clickable { onTokenVisibleChange(!aiTokenVisible) }
                                .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (aiTokenVisible) "🙈" else "👁",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        LabeledField(stringResource("ai_field_model")) {
            ModelComboBox(
                provider = providers.firstOrNull { it.id == aiProviderId },
                model = aiModel,
                onChange = onModelChange,
            )
        }
    }
}

/**
 * 共享 footer：第一步只有「取消」（点卡片即进第二步），第二步 AI「上一步 / 保存」
 * + 错误行、其余类型只有「上一步」（表单还没有，无东西可保存）。
 */
@Composable
private fun DialogFooter(
    step: NewConnectionStep,
    selectedIsAi: Boolean,
    saveError: String?,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (step == NewConnectionStep.CREATE && selectedIsAi && saveError != null) {
            Text(
                text = saveError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                NewConnectionStep.CREATE ->
                    if (selectedIsAi) {
                        PressableButton(
                            onClick = onBack,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ) { Text(stringResource("common_back")) }
                        Spacer(Modifier.width(8.dp))
                        PressableButton(onClick = onSave) { Text(stringResource("common_save")) }
                    } else {
                        PressableButton(onClick = onBack) { Text(stringResource("common_back")) }
                    }
            }
        }
    }
}
