package com.mqclient.aiui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mqclient.ai.AiProvider

/**
 * 可编辑的模型组合框：直接往框里敲，或打开菜单从 Provider 的 [AiProvider.defaultModels]
 * 里挑。Provider 未知（id 对不上注册表）时退化成纯文本输入。
 *
 * AI 专属控件所以归 ai-ui（新建连接表单与设置页的 AI 卡片共用）。
 * 放在 ai-ui 而不是某个 provider 的 ui 模块：两个 provider 都要用，放任何一边
 * 都会逼另一边横向依赖（Anthropic 表单曾经 `implementation(projects.openaiUi)`）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModelComboBox(
    provider: AiProvider?,
    model: String,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // 去重：预置候选在前，已保存的自定义值跟着，手填过的模型不会从列表里消失。
    val options = remember(provider, model) {
        ((provider?.defaultModels ?: emptyList()) + model).filter { it.isNotBlank() }.distinct()
    }
    // 输入时过滤候选，但始终保留正在输入的文本，全自定义的名字才始终可达。
    val visibleOptions = remember(options, model) {
        if (model.isBlank()) {
            options
        } else {
            val matches = options.filter { it.contains(model, ignoreCase = true) }
            if (matches.any { it.equals(model, ignoreCase = true) }) matches else matches + model
        }
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = model,
            onValueChange = {
                onChange(it)
                expanded = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            singleLine = true,
            placeholder = { Text(provider?.defaultModels?.firstOrNull().orEmpty()) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            visibleOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onChange(option)
                    },
                )
            }
        }
    }
}
