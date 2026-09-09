package com.mqclient.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource("settings_general_language"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = languageLabel(current),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    Text(
                        text = "▼",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickableSimple { expanded = true },
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Language.entries.forEach { lang ->
                            DropdownMenuItem(
                                text = { Text(languageLabel(lang)) },
                                onClick = {
                                    expanded = false
                                    onChange(lang)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A "label + text input + suffix" row used for all numeric settings.
 * Accepts digits only; clamps [onChange] to the [min]..[max] range so
 * the underlying config never holds an invalid value.
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
    var text by remember(value) { mutableStateOf(value.toString()) }
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
                        val parsed = newValue.toIntOrNull()
                        if (parsed != null) onChange(parsed.coerceIn(min, max))
                    }
                },
                modifier = Modifier.width(160.dp),
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

/* local convenience to dodge a fully-qualified call every time */
@Composable
private fun Modifier.clickableSimple(onClick: () -> Unit): Modifier =
    this.clickable { onClick() }