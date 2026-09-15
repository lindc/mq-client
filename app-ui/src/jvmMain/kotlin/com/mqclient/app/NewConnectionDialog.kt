package com.mqclient.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import com.mqclient.ai.AiAdapterRegistry
import com.mqclient.ai.AiProviders
import com.mqclient.core.AiConnection
import com.mqclient.core.AiConnectionRepository
import com.mqclient.core.ModuleRegistry
import com.mqclient.core.ServiceAdapter
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.app.settings.LabeledField
import com.mqclient.app.settings.ModelComboBox
import com.mqclient.app.settings.SettingsDropdownRow
import dev.nucleusframework.application.DecoratedDialog
import dev.nucleusframework.application.LocalNucleusWindow
import java.util.UUID

/**
 * 新建连接对话框：类目 tab **完全由 SPI 扫描**得出 —— [ModuleRegistry.services] 按
 * [ServiceAdapter.category] 分组（[ModuleRegistry.groupByCategory]），新模块带新
 * category 时 tab 自动出现，这里没有任何硬编码的类目清单。
 *
 * 两类 tab 内容：
 *  - **通用（MQ 等）**：该类目下服务类型的卡片单选。骨架期不做表单、不做保存，
 *    选中只是视觉反馈，底部提示表单在后续版本提供；
 *  - **AI**：openai 连接是独立的 [AiConnection] 体系（存 `ai.connections`，不经
 *    [com.mqclient.core.ConnectionStore]），tab 直接渲染可用的创建表单，落盘走
 *    [AiConnectionRepository.create]（token 由 ConfigManager 的名字匹配自动加密），
 *    成功后经 [onAiCreated] 交调用方同步内存里的 `appConfig` —— 不同步的话，设置
 *    对话框下次整份覆盖保存会把这条新连接抹掉。
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

    DecoratedDialog(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = DpSize(520.dp, 560.dp)),
        title = stringResource("menu_new_connection"),
        resizable = true,
    ) {
        val nucleusWindow = LocalNucleusWindow.current
        LaunchedEffect(nucleusWindow) { nucleusWindow.setMinimumSize(DpSize(480.dp, 420.dp)) }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                CategoryTabStrip(
                    tabs = categoryOrder,
                    selected = selectedCategory,
                    onSelect = { selectedCategory = it },
                )
                HorizontalDivider()
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    TabBody(
                        adapters = grouped[selectedCategory].orEmpty(),
                        defaultAiName = defaultAiName,
                        onDismiss = onDismiss,
                        onAiCreated = onAiCreated,
                    )
                }
            }
        }
    }
}

/** 横排类目 tab：手搓（M3 TabRow 定制不动），样式与 MainWindow 的 tab / 设置侧栏一致。 */
@Composable
private fun CategoryTabStrip(
    tabs: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val isSelected = tab == selected
                Text(
                    text = tab,
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else androidx.compose.ui.graphics.Color.Transparent,
                            )
                            .clickable { onSelect(tab) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        HorizontalDivider()
    }
}

/**
 * 单个类目 tab 的内容。AI 组（含 [AiProviders.OPENAI_COMPATIBLE] 对应的 adapter）
 * 渲染创建表单；其余类目渲染通用服务类型选择。
 */
@Composable
private fun TabBody(
    adapters: List<ServiceAdapter>,
    defaultAiName: String,
    onDismiss: () -> Unit,
    onAiCreated: (AiConnection) -> Unit,
) {
    val scrollState = rememberScrollState()
    val isAiTab = adapters.any { it.id == AiProviders.OPENAI_COMPATIBLE.id }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(scrollState),
    ) {
        if (isAiTab) {
            AiCreateForm(
                defaultName = defaultAiName,
                onDismiss = onDismiss,
                onAiCreated = onAiCreated,
            )
        } else {
            ServiceTypeList(adapters = adapters)
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource("new_connection_form_todo"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PressableButton(onClick = onDismiss) { Text(stringResource("common_close")) }
            }
        }
    }
}

/** 通用类目：服务类型卡片单选（骨架期，选中只是视觉反馈）。 */
@Composable
private fun ServiceTypeList(adapters: List<ServiceAdapter>) {
    var selectedId by remember(adapters) { mutableStateOf(adapters.firstOrNull()?.id) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        adapters.forEach { adapter ->
            val isSelected = adapter.id == selectedId
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color =
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable { selectedId = adapter.id }
                        .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.foundation.Image(
                    painter = middlewareIcon(adapter.id),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(adapter.name, style = MaterialTheme.typography.bodyMedium)
                    if (adapter.description.isNotBlank()) {
                        Text(
                            text = adapter.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * AI 连接创建表单（最小可用集）。字段状态就放在这里 —— 保存成功直接回调
 * [onAiCreated] 并关窗；失败留在本 tab 底部展示，对话框不关闭。
 */
@Composable
private fun AiCreateForm(
    defaultName: String,
    onDismiss: () -> Unit,
    onAiCreated: (AiConnection) -> Unit,
) {
    val providers = remember { AiAdapterRegistry.providers() }
    var saveError by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf(defaultName) }
    var providerId by remember { mutableStateOf(providers.firstOrNull()?.id.orEmpty()) }
    var baseUrl by remember { mutableStateOf(providers.firstOrNull()?.defaultBaseUrl.orEmpty()) }
    var token by remember { mutableStateOf("") }
    var tokenVisible by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(providers.firstOrNull()?.defaultModels?.firstOrNull().orEmpty()) }

    fun buildConnection(): AiConnection =
        AiConnection(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { defaultName },
            providerId = providerId,
            baseUrl = baseUrl.trim(),
            token = token,
            model = model.trim(),
        )

    Column(modifier = Modifier.fillMaxWidth()) {
        LabeledField(stringResource("conn_name_label")) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        Spacer(Modifier.height(8.dp))

        SettingsDropdownRow(
            label = stringResource("ai_field_provider"),
            currentLabel = providers.firstOrNull { it.id == providerId }?.name ?: providerId.ifBlank { "—" },
            options = providers,
            optionLabel = { it.name },
            onPick = { picked ->
                providerId = picked.id
                // 切 Provider 时把没填过的默认值补上；用户填过的值不动。
                if (baseUrl.isBlank()) baseUrl = picked.defaultBaseUrl
                if (model.isBlank()) model = picked.defaultModels.firstOrNull().orEmpty()
            },
        )
        Spacer(Modifier.height(8.dp))

        LabeledField(stringResource("ai_field_url")) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(providers.firstOrNull { it.id == providerId }?.defaultBaseUrl.orEmpty())
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        LabeledField(stringResource("ai_field_api_key")) {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                // 空字段也给个圆点提示「这里是密钥」；一开始输入就消失。
                placeholder = { Text("••••••••") },
                trailingIcon = {
                    Box(
                        modifier =
                            Modifier
                                .clickable { tokenVisible = !tokenVisible }
                                .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (tokenVisible) "🙈" else "👁",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))

        LabeledField(stringResource("ai_field_model")) {
            ModelComboBox(
                provider = providers.firstOrNull { it.id == providerId },
                model = model,
                onChange = { model = it },
            )
        }
        Spacer(Modifier.height(16.dp))

        if (saveError != null) {
            Text(
                text = saveError.orEmpty(),
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
            PressableButton(
                onClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text(stringResource("common_cancel")) }
            Spacer(Modifier.width(8.dp))
            PressableButton(
                onClick = {
                    // 与设置页保存同风格：同步落盘（create 是普通函数，非挂起）。
                    val connection = buildConnection()
                    AiConnectionRepository.create(connection)
                        .onSuccess {
                            onAiCreated(connection)
                            onDismiss()
                        }
                        .onFailure { error ->
                            saveError = error.message ?: error::class.simpleName
                        }
                },
            ) { Text(stringResource("common_save")) }
        }
    }
}
