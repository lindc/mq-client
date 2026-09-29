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
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogState
import com.mqclient.ibmmq.ui.properties.QueuePropertyGroup
import com.mqclient.ibmmq.ui.properties.ResolvedQueueProperty
import com.mqclient.ibmmq.ui.properties.resolveQueuePropertyGroups
import com.mqclient.uicommon.i18n.stringResource

/**
 * 队列属性内容：布局对齐 MQ Explorer 的 Queue properties 对话框——
 * 左侧分组列表（常规 / 扩展 / 集群 / 触发 / 事件 / 存储 / 统计信息 / 全部属性），
 * 右侧属性行：**属性名（i18n）+ 值（MQ 常量转本地化说明）+ 一句话说明（i18n，小字）**。
 *
 * 全部文案走 i18n（`queue_prop_name_*` / `queue_prop_desc_*` / `queue_value_*`，
 * 提取自本机 MQ Explorer 9.4.5 官方帮助文档 en / zh-CN 双语版）；数据只读慢扫缓存。
 */
@Composable
fun QueuePropertiesContent(state: ObjectPropertiesDialogState) {
    val snapshot = remember(state.connectionId, state.objectName) {
        IbmMQModule.serviceOrNull()
            ?.cachedObjects(state.connectionId, MqObjectType.QUEUE)
            ?.firstOrNull { it.name == state.objectName }
    }

    if (snapshot == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("properties_placeholder_message"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val groups = remember(snapshot) { resolveQueuePropertyGroups(snapshot) }
    var selected by remember(snapshot) {
        mutableStateOf(groups.firstOrNull()?.first?.titleKey ?: "queue_props_group_general")
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
private fun PropertyRowLocalized(row: ResolvedQueueProperty, modifier: Modifier = Modifier) {
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
