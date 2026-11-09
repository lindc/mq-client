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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mqclient.ibmmq.ui.properties.ObjectPropertyGroup
import com.mqclient.ibmmq.ui.properties.ResolvedObjectProperty
import com.mqclient.uicommon.PropertyItem
import com.mqclient.uicommon.PropertyTable
import com.mqclient.uicommon.i18n.stringResource

/**
 * 属性分组面板（queue / listener 属性对话框共用）：布局对齐 MQ Explorer 的
 * properties 对话框——左侧分组列表，右侧属性行：属性名（i18n）+ 一句话说明 +
 * 值（MQ 常量转本地化说明）。
 *
 * [customPanes]：额外的**自渲染**分组（键 = 分组标题 i18n 键，值 = 面板内容），
 * 排在静态分组之后出现在左侧列表里，选中时右侧渲染面板本身而不是属性行。
 * 通道属性对话框用它挂「运行状态」分组（实时数据 + 操作按钮，静态属性行装不下）；
 * 其余对象类型不传，行为与原来完全一致。
 */
@Composable
internal fun PropertyGroupsPane(
    groups: List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>>,
    defaultSelectionKey: String,
    customPanes: Map<String, @Composable () -> Unit> = emptyMap(),
) {
    var selected by remember(groups) {
        mutableStateOf(groups.firstOrNull()?.first?.titleKey ?: defaultSelectionKey)
    }

    // 自定义分组里不属于静态分组的（正常情况全部如此），追加在左侧列表末尾。
    val customEntries = customPanes.filterKeys { key -> groups.none { it.first.titleKey == key } }

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
                GroupEntry(titleKey = group.titleKey, selected = group.titleKey == selected) { selected = group.titleKey }
            }
            customEntries.keys.forEach { titleKey ->
                GroupEntry(titleKey = titleKey, selected = titleKey == selected) { selected = titleKey }
            }
        }

        // 右：所选分组的内容（滚动）——自定义面板优先，否则渲染属性行。
        // 右侧不留边距：斑马纹背景贴到对话框最右缘（行内自带 10dp 内边距，文字不顶边）。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 20.dp, bottom = 20.dp),
        ) {
            val customPane = customEntries[selected] ?: customPanes[selected]
            if (customPane != null) {
                customPane()
                return@Column
            }
            val rows = groups
                .firstOrNull { it.first.titleKey == selected }
                ?.second
                .orEmpty()
            if (rows.isEmpty()) {
                Text(
                    text = stringResource("ibm_mq_properties_placeholder_message"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 叶子行统一走 ui-common PropertyTable（斑马纹 + 左对齐 + 行尾复制）：
            // 名称 / 说明 / 值的 i18n 解析在组合期完成（缺失回落英文名 / 原始值）。
            // maxWidth = Infinity：面板走满窗铺满布局，表格要填满整个右列（右列已由
            // Row 的 weight(1f) 吃掉剩余宽度）—— 默认 900dp 上限会让表格停在列中间，
            // 右侧留出一大片空白（斑马纹断在半路）。
            PropertyTable(
                items = rows.map { row ->
                    val name = stringResource(row.nameKey).let { if (it == row.nameKey) row.labelEn else it }
                    val desc = stringResource(row.descKey).let { if (it == row.descKey) null else it }
                    val value = listOfNotNull(row.valueKey, row.valueFallbackKey)
                        .map { key -> key to stringResource(key) }
                        .firstOrNull { (k, text) -> text != k }
                        ?.second
                        ?: row.rawValue
                    PropertyItem(key = name, value = value, description = desc)
                },
                maxWidth = Dp.Infinity,
            )
        }
    }
}

/** 左侧列表的分组条目（选中态高亮 + 点击切换）。 */
@Composable
private fun GroupEntry(titleKey: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = stringResource(titleKey),
        style = MaterialTheme.typography.bodyMedium,
        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    )
}
