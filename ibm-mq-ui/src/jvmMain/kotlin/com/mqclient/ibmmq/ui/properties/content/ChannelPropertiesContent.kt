package com.mqclient.ibmmq.ui.properties.content

import androidx.compose.runtime.Composable
import com.mqclient.ibmmq.ui.properties.ObjectPropertiesDialogState
import com.mqclient.ibmmq.ui.properties.PropertiesPlaceholder
import com.mqclient.uicommon.i18n.stringResource

/**
 * 通道属性内容（占位）。TODO 实装：CHLTYPE / XMITQ / RQMNAME / CONNAME / 状态等通道属性。
 */
@Composable
fun ChannelPropertiesContent(state: ObjectPropertiesDialogState) {
    PropertiesPlaceholder(
        objectName = state.objectName,
        objectType = stringResource("object_type_channel"),
    )
}
