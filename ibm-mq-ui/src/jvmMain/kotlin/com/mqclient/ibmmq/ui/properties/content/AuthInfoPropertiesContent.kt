package com.mqclient.ibmmq.ui.properties.content

import androidx.compose.runtime.Composable
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogState
import com.mqclient.ibmmq.ui.properties.PropertiesPlaceholder
import com.mqclient.uicommon.i18n.stringResource

/**
 * 认证信息属性内容（占位）。TODO 实装：AUTHTYPE / LDAP 属性等认证信息。
 */
@Composable
fun AuthInfoPropertiesContent(state: ObjectPropertiesDialogState) {
    PropertiesPlaceholder(
        objectName = state.objectName,
        objectType = stringResource("object_type_auth_info"),
    )
}
