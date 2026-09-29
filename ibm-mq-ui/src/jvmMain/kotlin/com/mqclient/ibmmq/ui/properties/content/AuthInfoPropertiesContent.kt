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
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogState
import com.mqclient.ibmmq.ui.properties.resolveAuthInfoPropertyGroups
import com.mqclient.uicommon.i18n.stringResource

/**
 * 认证信息属性内容：数据取只读慢扫缓存，分组解析见 [resolveAuthInfoPropertyGroups]，
 * 布局走共用的 [PropertyGroupsPane]。
 */
@Composable
fun AuthInfoPropertiesContent(state: ObjectPropertiesDialogState) {
    val snapshot = remember(state.connectionId, state.objectName) {
        IbmMQModule.serviceOrNull()
            ?.cachedObjects(state.connectionId, MqObjectType.AUTH_INFO)
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

    val groups = remember(snapshot) { resolveAuthInfoPropertyGroups(snapshot) }
    PropertyGroupsPane(groups = groups, defaultSelectionKey = "authinfo_props_group_general")
}
