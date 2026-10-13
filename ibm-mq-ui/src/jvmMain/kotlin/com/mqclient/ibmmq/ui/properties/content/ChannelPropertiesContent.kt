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
import com.mqclient.ibmmq.ui.properties.resolveChannelPropertyGroups
import com.mqclient.uicommon.i18n.stringResource

/**
 * 通道属性内容（[MqObjectPropertiesContent] 的通道专用变体）：静态分组（常规 /
 * 全部属性，来自慢扫缓存的 DISPLAY CHANNEL 快照）与 [MqObjectPropertiesContent]
 * 完全一致；额外经 [PropertyGroupsPane] 的 customPanes 挂第三个分组「运行状态」
 * （[ChannelRuntimeStatusPane]，实时 DISPLAY CHSTATUS，不经缓存）——
 * 两类信息严格分层，不与定义属性混排。
 */
@Composable
internal fun ChannelPropertiesContent(
    state: ObjectPropertiesDialogState,
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

    val groups = remember(snapshot) {
        val static = resolveChannelPropertyGroups(snapshot)
        // 「运行状态」排在「全部属性」之前（信息分层：常规 → 运行状态 → 全部属性）。
        // 合成组的 titleKey 与 customPanes 的键相同：左侧列表按位置渲染条目，
        // 右侧选中时命中 customPanes 渲染实时面板。全部属性不存在（罕见）时追加尾部。
        val runtimeGroup = ObjectPropertyGroup(RUNTIME_GROUP_KEY, emptyList()) to emptyList<ResolvedObjectProperty>()
        val allIndex = static.indexOfFirst { it.first.titleKey == "ibm_mq_channel_props_group_all" }
        if (allIndex < 0) static + runtimeGroup
        else static.subList(0, allIndex) + runtimeGroup + static.subList(allIndex, static.size)
    }
    PropertyGroupsPane(
        groups = groups,
        defaultSelectionKey = "ibm_mq_channel_props_group_general",
        customPanes = mapOf(
            RUNTIME_GROUP_KEY to {
                ChannelRuntimeStatusPane(
                    connectionId = state.connectionId,
                    channelName = state.objectName,
                    channelType = snapshot.channelType,
                )
            },
        ),
    )
}

/** 运行状态分组的标题键（合成组 + customPanes 面板共用同一键）。 */
private const val RUNTIME_GROUP_KEY = "ibm_mq_channel_props_group_runtime"
