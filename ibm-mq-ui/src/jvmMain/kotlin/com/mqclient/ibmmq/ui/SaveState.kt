package com.mqclient.ibmmq.ui

/**
 * 保存链路的状态机：Idle → Testing → (成功落盘 | Failed) → Idle。
 *
 * 「保存」按钮（宿主 footer 的「完成」）在 [Testing] 时经
 * [com.mqclient.core.ConnectionFormState.busy] 禁用；[Failed] 时由面板弹出
 * 「是否仍要保存」确认框 —— 用户选保存走 [IbmMqConnectionFormState.confirmSave]，
 * 选取消走 [IbmMqConnectionFormState.dismissFailed] 回到 [Idle]。
 *
 * 保存成功不设状态：宿主经 onSaved 回调直接关窗。
 */
sealed class SaveState {
    /** 空闲：表单可编辑，保存可触发。 */
    object Idle : SaveState()

    /** 正在按当前表单配置测试连接（15 秒超时），保存 / 重复提交被挡住。 */
    object Testing : SaveState()

    /** 测试失败（含超时）：面板弹确认框，等用户决定仍要保存还是回去改。 */
    data class Failed(val message: String) : SaveState()
}
