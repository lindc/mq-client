package com.mqclient.app.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.mqclient.core.GeneralConfig
import com.mqclient.core.Language
import com.mqclient.uicommon.i18n.stringResource

/**
 * General settings tab — language and history-retention window.
 *
 * Each input is a self-contained composable that only re-emits when the
 * user's edit produces a valid value, so the parent doesn't see drafts that
 * would fail validation downstream.
 */
@Composable
internal fun GeneralSettingsPane(
    value: GeneralConfig,
    onChange: (GeneralConfig) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource("settings_tab_general"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        LanguageRow(value.language) { onChange(value.copy(language = it)) }
        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        NumberRow(
            label = stringResource("history_retention_label") +
                " [${value.historyRetentionDays}] " + stringResource("unit_days") +
                " " + stringResource("history_retention_note"),
            suffix = stringResource("unit_days"),
            value = value.historyRetentionDays,
            min = 7,
            max = 365,
            onChange = { onChange(value.copy(historyRetentionDays = it)) },
        )
    }
}

/** Enum → string key mapping, so the language labels follow the loaded language table. */
private fun languageLabel(lang: Language): String = when (lang) {
    Language.SIMPLIFIED_CHINESE -> stringResource("language_simplified_chinese")
    Language.ENGLISH -> stringResource("language_english")
}

@Composable
private fun LanguageRow(
    current: Language,
    onChange: (Language) -> Unit,
) {
    SettingsDropdownRow(
        label = stringResource("settings_general_language"),
        currentLabel = languageLabel(current),
        options = Language.entries,
        optionLabel = ::languageLabel,
        onPick = onChange,
    )
}

/**
 * A "label + text input + suffix" row used for all numeric settings.
 *
 * 输入期间以文本框内容为准，只把落在 [min]..[max] 里的值上报给 [onChange]；越界 / 空的
 * 草稿先留在框里，失焦时再归一化（clamp 或落回上一个合法值）—— 这样底层 config 永远
 * 拿不到非法值，用户也能正常输入多位数。
 */
@Composable
internal fun NumberRow(
    label: String,
    suffix: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    // 输入期间 text 是唯一真相：不能再按 value 重建 —— coerceIn 会把中间态夹住
    // （字号 12..18 里敲 "1" 会被夹成 12）→ value 变 → 状态重建 → 想输的数字永远打不出来。
    // 只在「没在编辑」时跟随外部值（切换配置 / 恢复默认）。
    var text by remember { mutableStateOf(value.toString()) }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(value, hasFocus) {
        if (!hasFocus) text = value.toString()
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { newValue ->
                    if (newValue.isEmpty() || newValue.all { it.isDigit() }) {
                        text = newValue
                        // 不 clamp：只有落在范围内的中间值才提交给父级，
                        // 越界的草稿先留在输入框里，失焦时再归一化。
                        newValue.toIntOrNull()?.takeIf { it in min..max }?.let(onChange)
                    }
                },
                modifier = Modifier
                    .width(160.dp)
                    .onFocusChanged { focusState ->
                        hasFocus = focusState.isFocused
                        if (!focusState.isFocused) {
                            // 失焦即归一化：越界或空 → 落回最近提交过的合法值。
                            val normalized = text.toIntOrNull()?.coerceIn(min, max) ?: value
                            text = normalized.toString()
                            onChange(normalized)
                        }
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$suffix ${stringResource("common_range_format").format(min, max)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
