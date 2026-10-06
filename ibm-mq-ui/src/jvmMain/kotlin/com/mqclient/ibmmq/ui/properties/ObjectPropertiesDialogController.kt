package com.mqclient.ibmmq.ui.properties

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mqclient.ibmmq.pcf.MqObjectType

/**
 * 「打开对象属性对话框」的**统一入口**：对象列表页的属性按钮 / 双击卡片与拓扑图的
 * 双击节点都经它打开（原先各面板各持一份 `var propertiesDialog by remember` +
 * `MqObjectPropertiesDialog` 渲染，收拢于此）。
 *
 * `objectType → 内容组件` 的分派在 [MqObjectPropertiesDialog] 的 when 处，新增对象
 * 类型只需在那里加一个分支，所有调用方无需改动。
 */
@Stable
class ObjectPropertiesDialogController(private val connectionId: String) {

    var state by mutableStateOf<ObjectPropertiesDialogState?>(null)
        private set

    /** 打开属性对话框（重复打开直接切换状态，旧对话框被新状态替换）。 */
    fun open(objectType: MqObjectType, objectName: String) {
        state = ObjectPropertiesDialogState(
            connectionId = connectionId,
            objectType = objectType,
            objectName = objectName,
        )
    }

    /** 关闭并清空状态。 */
    fun close() {
        state = null
    }
}

/** 按连接创建并记住控制器（连接 id 变化即重建，状态不跨连接泄漏）。 */
@Composable
fun rememberObjectPropertiesDialogController(connectionId: String): ObjectPropertiesDialogController =
    remember(connectionId) { ObjectPropertiesDialogController(connectionId) }

/**
 * 属性对话框宿主：`controller.state` 非空时渲染对话框，关闭时经 [ObjectPropertiesDialogController.close]
 * 清空。调用方把它放在组合树任意位置（对话框是独立窗口，落点不影响视觉）。
 */
@Composable
fun ObjectPropertiesDialogHost(controller: ObjectPropertiesDialogController) {
    controller.state?.let { dialogState ->
        MqObjectPropertiesDialog(
            state = dialogState,
            onDismiss = controller::close,
        )
    }
}
