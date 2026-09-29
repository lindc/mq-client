package com.mqclient.ibmmq.ui.properties.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.ibmmq.ui.properties.ObjectPropertyGroup
import com.mqclient.ibmmq.ui.properties.ResolvedObjectProperty
import com.mqclient.uicommon.i18n.stringResource

/**
 * 属性分组面板（queue / listener 属性对话框共用）：布局对齐 MQ Explorer 的
 * properties 对话框——左侧分组列表，右侧属性行：属性名（i18n）+ 一句话说明 +
 * 值（MQ 常量转本地化说明）。
 */
@Composable
internal fun PropertyGroupsPane(
    groups: List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>>,
    defaultSelectionKey: String,
) {
    var selected by remember(groups) {
        mutableStateOf(groups.firstOrNull()?.first?.titleKey ?: defaultSelectionKey)
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // 左：分组列表（SettingsDialog.SideNav 风格）
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(148.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 8.dp),
        ) {
            groups.forEach { (group, _) ->
                val isSelected = group.titleKey == selected
                Text(
                    text = stringResource(group.titleKey),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .clickable { selected = group.titleKey }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }

        // 右：所选分组的属性行（滚动）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            val rows = groups
                .firstOrNull { it.first.titleKey == selected }
                ?.second
                .orEmpty()
            if (rows.isEmpty()) {
                Text(
                    text = stringResource("properties_placeholder_message"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            rows.forEach { row ->
                PropertyRowLocalized(
                    row = row,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                )
            }
        }
    }
}

/**
 * 属性行：左 = 属性名（i18n，缺失回落英文名）+ 一句话说明（i18n，小字，最多两行）；
 * 右 = 值（先查策展 queue_value_* 键，缺失查 <常量>.title 兜底键，再缺显示原始值）。
 */
@Composable
private fun PropertyRowLocalized(row: ResolvedObjectProperty, modifier: Modifier = Modifier) {
    val name = stringResource(row.nameKey).let { if (it == row.nameKey) row.labelEn else it }
    val desc = stringResource(row.descKey).let { if (it == row.descKey) "" else it }
    val value = listOfNotNull(row.valueKey, row.valueFallbackKey)
        .map { key -> key to stringResource(key) }
        .firstOrNull { (key, text) -> text != key }
        ?.second
        ?: row.rawValue

    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (desc.isNotBlank()) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(160.dp),
        )
    }
}
