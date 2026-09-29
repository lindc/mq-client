package com.mqclient.ibmmq.ui.properties

import com.mqclient.ibmmq.pcf.MqObjectType

/**
 * 对象属性对话框的状态：定位「哪个连接的哪个对象」。
 *
 * 由调用方（对象列表 Panel / 拓扑 Panel）持有并按需创建；
 * 关闭对话框时调用方把状态清空（`propertiesDialog = null`）。
 */
data class ObjectPropertiesDialogState(
    val connectionId: String,
    val objectType: MqObjectType,
    val objectName: String,
)
