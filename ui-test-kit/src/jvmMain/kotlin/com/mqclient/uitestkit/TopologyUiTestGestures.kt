@file:OptIn(ExperimentalTestApi::class)

package com.mqclient.uitestkit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import kotlin.math.abs

/**
 * Compose UI 测试的画布手势工具（各 UI 模块 jvmTest 共用；拓扑节点拖拽等场景）。
 *
 * 平台坑（CMP 1.12 实测）：
 *  - 语义定位用 `positionInRoot + size/2` 求中心 —— `SemanticsNode.boundsInRoot` 在
 *    SubcomposeLayout 放置下恒返回零矩形；
 *  - 鼠标注入一律对 `onRoot()` 按绝对根坐标注入 —— 对节点 interaction 注入同样受
 *    零 bounds 影响；
 *  - runComposeUiTest 的桌面窗口固定 1024×768，超出窗口的内容拖拽前需 [panIntoView]
 *    先平移进窗口。
 */
/** 测试窗口（runComposeUiTest 桌面 actual 固定值）的中心 / 底部空白锚点。 */
private val WINDOW_CENTER = Offset(512f, 384f)
private val WINDOW_BOTTOM_SPARE = Offset(512f, 740f)

/** 语义节点 / 任意带 testTag 的节点中心（根坐标）。 */
fun ComposeUiTest.centerOf(tag: String): Offset {
    val n = onNodeWithTag(tag).fetchSemanticsNode()
    return n.positionInRoot + Offset(n.size.width / 2f, n.size.height / 2f)
}

/**
 * 在根坐标 [from] 按下，先走 1px 越 slop，再走主位移 [dx]/[dy] 像素后松手
 * （贴合真实拖动轨迹：slop 会吃掉开头一小段）。
 */
fun ComposeUiTest.dragAt(from: Offset, dx: Float, dy: Float) {
    onRoot().performMouseInput {
        moveTo(from)
        press()
        moveBy(Offset(0f, 1f))
        moveBy(Offset(dx, dy - 1f))
        release()
    }
}

/**
 * 从画布空白处（窗口底部边缘）做一次画布平移手势，把 [tag] 节点移到 [target]
 * （默认窗口中心）附近，返回平移后的节点中心。平移 1:1 跟手，路径不经过节点
 * 也不影响后续断言 —— 与被测的节点拖拽互不干扰。节点已在 [target] 附近（<1px）
 * 时短路不做手势。
 */
fun ComposeUiTest.panIntoView(tag: String, target: Offset = WINDOW_CENTER): Offset {
    val before = centerOf(tag)
    if (abs(before.x - target.x) < 1f && abs(before.y - target.y) < 1f) return before
    dragAt(from = WINDOW_BOTTOM_SPARE, dx = target.x - before.x, dy = target.y - before.y)
    waitForIdle()
    return centerOf(tag)
}
