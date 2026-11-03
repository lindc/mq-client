package com.mqclient.uicommon.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.nucleusframework.application.LocalNucleusWindow

/**
 * 对话框「确定关闭」的统一出口：**先同步隐藏原生窗口，再走状态回调**。
 *
 * ## 为什么不是直接调 onDismiss
 *
 * 本应用的主线程是 nucleus / Tao 自绘框架（见 `doc/compose-frame-latches.md`）。
 * 一帧只有 native 事件循环派发 `RedrawRequested` 时才发生，而 `TaoWindow.requestRedraw()`
 * 有一道 `redrawPending` 闩锁：**只放行一次**，之后所有请求被静默丢弃，复位只依赖
 * 那个迟迟不来的 native 事件。
 *
 * 于是出现这条现场：「点 ✕ / 关闭毫无反应，拖一下窗口立刻关掉」。点击只写了一个
 * MutableState（`detailMessage = null`），而**移除这个原生窗口**要等父组合重组 ——
 * 重组要一帧 —— 帧被闩锁挡着。拖窗口产生真实 native 事件，帧才派下来，窗口才消失。
 *
 * `NucleusWindow.hide()` 走的是 `NativeTaoBridge.nativeSetVisible(handle, false)`：
 * **直接的系统调用，不经过 Compose 的帧管线**。所以先 hide 再 onDismiss，用户
 * 立刻看到窗口消失；父组合的重组（真正销毁窗口）等到下一帧再完成，什么时候来都无所谓。
 *
 * ## 为什么顺序不能反
 *
 * `onDismiss` 通常只是写一个状态，不会抛；但**万一它抛**（宿主回调里的 bug），
 * 先 hide 保证了「窗口已经关了」这个用户可见的事实不会因为回调出错而丢失。
 *
 * ## 已知残余
 *
 * 若帧长期不来，窗口只是「隐藏」而非「销毁」——父组合仍认为它开着。但这不构成新问题：
 * 用户要再操作就得动鼠标 / 点键盘，那些本身就是 native 事件，挂起的重组会在那时完成。
 */
internal fun dismissWithWindow(hide: () -> Unit, onDismiss: () -> Unit) {
    runCatching { hide() }
        .onFailure { /* hide 失败（窗口已销毁 / 平台不支持）也必须继续关 */ }
    onDismiss()
}

/**
 * 拿一个「先隐藏当前窗口、再回调」的关闭动作（[dismissWithWindow] 的组合期封装）。
 *
 * **只能在对话框内容里调用**：`LocalNucleusWindow` 没有默认值，在窗口外读会抛
 * （与 `ObjectDetailDialog` 里读它的位置相同 —— 都在 `DecoratedDialog` 的 content 内）。
 */
@Composable
fun rememberWindowDismiss(onDismiss: () -> Unit): () -> Unit {
    val window = LocalNucleusWindow.current
    return remember(window, onDismiss) {
        { dismissWithWindow(hide = { window.hide() }, onDismiss = onDismiss) }
    }
}
