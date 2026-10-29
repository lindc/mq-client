package com.mqclient.uicommon.dialog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 关闭动作的顺序与容错（[dismissWithWindow]）。
 *
 * 背景（`doc/compose-frame-latches.md`）：本应用主线程是 nucleus/Tao 自绘框架，
 * 一帧只在 native 派发 `RedrawRequested` 时发生，而 `TaoWindow.requestRedraw()`
 * 有 `redrawPending` 闩锁 —— 只放行一次，复位依赖那个迟迟不来的事件。
 *
 * 于是「点关闭只写状态」会卡住：**移除原生窗口**需要父组合重组 → 重组要一帧 → 帧不来。
 * 现场表现就是「点 ✕ 没反应，拖一下窗口立刻关上」。
 *
 * `NucleusWindow.hide()` 是 `nativeSetVisible(handle, false)`，不经过帧管线，
 * 所以「先 hide 再回调」能让窗口立刻消失。
 *
 * 这两条顺序性质就是这个修法的全部正确性依据，值得钉住（它们纯逻辑，可无头测）。
 */
class DialogDismissTest {

    @Test
    fun `window is hidden before the dismissal callback runs`() {
        val order = mutableListOf<String>()
        dismissWithWindow(
            hide = { order += "hide" },
            onDismiss = { order += "dismiss" },
        )
        assertEquals(
            listOf("hide", "dismiss"),
            order,
            "必须先隐藏窗口再回调：回调可能抛，先 hide 才能保证「已经关了」这个可见事实",
        )
    }

    @Test
    fun `dismissal still runs when hiding the window throws`() {
        // 窗口已被销毁 / 平台不支持 hide：绝不能因此关不掉对话框。
        var dismissed = false
        dismissWithWindow(
            hide = { error("window already disposed") },
            onDismiss = { dismissed = true },
        )
        assertTrue(dismissed, "hide 抛异常也必须完成关闭")
    }

    @Test
    fun `dismissal runs exactly once`() {
        var count = 0
        dismissWithWindow(hide = {}, onDismiss = { count++ })
        assertEquals(1, count)
    }
}
