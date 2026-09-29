package com.mqclient.ibmmq.ui.properties.content

import androidx.compose.runtime.Composable
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogState
import com.mqclient.ibmmq.ui.properties.PropertiesPlaceholder
import com.mqclient.uicommon.i18n.stringResource

/**
 * 服务属性内容（占位）。TODO 实装：STARTCMD / STARTARG 等服务属性。
 */
@Composable
fun ServicePropertiesContent(state: ObjectPropertiesDialogState) {
    PropertiesPlaceholder(
        objectName = state.objectName,
        objectType = stringResource("object_type_service"),
    )
}
