package com.mqclient.ibmmq.ui.properties.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogState
import com.mqclient.ibmmq.ui.properties.ObjectPropertyGroup
import com.mqclient.ibmmq.ui.properties.ResolvedObjectProperty
import com.mqclient.uicommon.i18n.stringResource

/**
 * MQ 对象属性内容的**通用实现**（此前为 10 份逐字复制的 `XxxPropertiesContent`，
 * 2026-10 收拢于此，行为不变）：数据取只读慢扫缓存（按 [state.objectType] 查缓存、
 * 按对象名命中），命中后经 [resolveGroups]（各对象类型的分组解析器）产出分组，
 * 布局走共用的 [PropertyGroupsPane]；快照缺失（该对象尚未被慢扫收录）显示占位文案。
 *
 * 各对象类型只在 [com.mqclient.ibmmq.ui.properties.MqObjectPropertiesDialog] 的
 * when 分发处给出一行配置：defaultSelectionKey + 分组解析器。
 */
@Composable
internal fun MqObjectPropertiesContent(
    state: ObjectPropertiesDialogState,
    defaultSelectionKey: String,
    resolveGroups: (MqObjectSnapshot) -> List<Pair<ObjectPropertyGroup, List<ResolvedObjectProperty>>>,
) {
    val snapshot = remember(state.connectionId, state.objectType, state.objectName) {
        IbmMQModule.serviceOrNull()
            ?.cachedObjects(state.connectionId, state.objectType)
            ?.firstOrNull { it.name == state.objectName }
    }

    if (snapshot == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource("ibm_mq_properties_placeholder_message"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val groups = remember(snapshot) { resolveGroups(snapshot) }
    PropertyGroupsPane(groups = groups, defaultSelectionKey = defaultSelectionKey)
}
