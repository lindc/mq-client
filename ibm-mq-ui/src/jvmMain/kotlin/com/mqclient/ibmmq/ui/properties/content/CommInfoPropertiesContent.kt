package com.mqclient.ibmmq.ui.properties.content

import androidx.compose.runtime.Composable
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogState
import com.mqclient.ibmmq.ui.properties.PropertiesPlaceholder
import com.mqclient.uicommon.i18n.stringResource

/**
 * 通信信息属性内容（占位）。TODO 实装：COMMIT / BATCH 等通信信息属性。
 */
@Composable
fun CommInfoPropertiesContent(state: ObjectPropertiesDialogState) {
    PropertiesPlaceholder(
        objectName = state.objectName,
        objectType = stringResource("object_type_comm_info"),
    )
}
