package com.mqclient.ibmmq.ui.properties.content

import androidx.compose.runtime.Composable
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogState
import com.mqclient.ibmmq.ui.properties.PropertiesPlaceholder
import com.mqclient.uicommon.i18n.stringResource

/**
 * 名称列表属性内容（占位）。TODO 实装：NAMES 列表内容。
 */
@Composable
fun NamelistPropertiesContent(state: ObjectPropertiesDialogState) {
    PropertiesPlaceholder(
        objectName = state.objectName,
        objectType = stringResource("object_type_namelist"),
    )
}
