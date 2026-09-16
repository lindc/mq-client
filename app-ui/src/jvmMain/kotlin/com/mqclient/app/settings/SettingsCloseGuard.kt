package com.mqclient.app.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 设置对话框的关闭拦截状态机 —— ESC / ✕ / 取消 / Alt+F4 所有关闭入口共用的
 * 唯一路径（[requestClose]）：
 *
 *  - 草稿干净（`isDirty = false`）→ 立即调 [onClose]，不弹确认；
 *  - 草稿改过 → 只弹确认（[confirmingClose] = true），**绝不**直接关窗 —— 关不关由
 *    用户在确认弹窗里选：[discardChanges] / [saveSucceeded] 关，
 *    [saveFailed] / [keepEditing] 留。
 *
 * 独立成类是为了脱离 Composable 直接单测（同 [com.mqclient.aipanel.AiPanelState]
 * 的做法）；用 mutableStateOf 让 UI 直接观察状态变化。
 */
internal class SettingsCloseGuard(private val onClose: () -> Unit) {

    /** 关闭确认弹窗是否可见；只在草稿改过时置 true。 */
    var confirmingClose by mutableStateOf(false)
        private set

    /** 所有关闭入口的唯一路径：脏 → 弹确认；干净 → 直接关。 */
    fun requestClose(isDirty: Boolean) {
        if (isDirty) confirmingClose = true else onClose()
    }

    /** 「继续编辑」：收起确认弹窗，回到设置界面。 */
    fun keepEditing() {
        confirmingClose = false
    }

    /** 「放弃更改」：收起弹窗并关窗（改动丢弃）。 */
    fun discardChanges() {
        confirmingClose = false
        onClose()
    }

    /** 「保存并关闭」且落盘成功：收起弹窗并关窗。 */
    fun saveSucceeded() {
        confirmingClose = false
        onClose()
    }

    /** 「保存并关闭」但落盘失败：收起弹窗回到设置界面（错误由 UI 展示），不关窗。 */
    fun saveFailed() {
        confirmingClose = false
    }
}
