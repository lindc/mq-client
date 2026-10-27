package com.mqclient.mqui.common

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `rememberDebouncedValue` 的**时序**契约（真实 Compose 运行时 + 虚拟时钟）。
 *
 * 这条逻辑现在被 8 处调用（4 个对象列表页 + 3 个消息浏览页），而它之前的实现是
 * 三份手写的 `LaunchedEffect { delay(300) }`，一行测试都没有。防抖的定义就是「时间」，
 * 所以必须用 `mainClock` 推进虚拟时间，而不是真的 sleep。
 *
 * 三件事必须成立：
 *  1. **首帧立即生效** —— 不能延迟首屏（否则进入页面第一眼看到的是空列表）；
 *  2. **窗口内不生效** —— 敲键过程中的中间态不该触发过滤（这是防抖的全部意义）；
 *  3. **清空立即生效** —— 「取消筛选」是明确动作，等 300ms 会让人以为没反应。
 */
@OptIn(ExperimentalTestApi::class)
class DebouncedValueTest {

    /**
     * 最小宿主：把输入 / 生效值都渲染成文本，断言即可读。
     * 输入用外部可变状态驱动（模拟用户在搜索框里敲字）。
     */
    @Composable
    private fun Host(input: String, onApplied: (String) -> Unit) {
        val applied = rememberDebouncedValue(input)
        onApplied(applied)
        Text(applied)
    }

    @Test
    fun `first value is applied immediately`() = runComposeUiTest {
        val applied = mutableListOf<String>()
        setContent { Host(input = "abc") { applied += it } }
        waitForIdle()

        // 首帧就应当是 "abc"（不是空串，也不是等 300ms 之后）。
        assertEquals("abc", applied.last(), "首次进入必须立即生效")
    }

    @Test
    fun `value waits for the debounce window before applying`() = runComposeUiTest {
        var input by mutableStateOf("abc")
        var lastApplied = ""
        setContent {
            val applied = rememberDebouncedValue(input)
            lastApplied = applied
            Text(applied)
        }
        waitForIdle()
        assertEquals("abc", lastApplied, "首帧生效")

        input = "abcd"

        // 299ms：窗口未满，生效值必须还是旧值。
        mainClock.advanceTimeBy(299)
        waitForIdle()
        assertEquals("abc", lastApplied, "299ms 时还不该生效")

        // 再 1ms 到点。
        mainClock.advanceTimeBy(1)
        waitForIdle()
        assertEquals("abcd", lastApplied, "300ms 到点后生效")
    }

    @Test
    fun `fast typing only applies the final value`() = runComposeUiTest {
        var input by mutableStateOf("a")
        var applyCount = 0
        var lastApplied = ""
        setContent {
            val applied = rememberDebouncedValue(input)
            applyCount++
            lastApplied = applied
            Text(applied)
        }
        waitForIdle()

        // 连打三下，每 50ms 一次：窗口一直被打断，最终只应生效最后的值。
        input = "ab"; mainClock.advanceTimeBy(50)
        input = "abc"; mainClock.advanceTimeBy(50)
        input = "abcd"; mainClock.advanceTimeBy(50)
        waitForIdle()
        assertEquals("a", lastApplied, "窗口内不应生效（仍是首帧的值）")

        mainClock.advanceTimeBy(300)
        waitForIdle()
        assertEquals("abcd", lastApplied, "只生效最终值，中间态被丢弃")
    }

    @Test
    fun `clearing the input applies immediately`() = runComposeUiTest {
        var input by mutableStateOf("abc")
        var lastApplied = ""
        setContent {
            val applied = rememberDebouncedValue(input)
            lastApplied = applied
            Text(applied)
        }
        waitForIdle()
        assertEquals("abc", lastApplied)

        // 清空 = 取消筛选，不该有滞后感（对着空列表等 300ms 很像卡住）。
        input = ""
        waitForIdle()

        assertEquals("", lastApplied, "清空必须立即生效")
    }

    @Test
    fun `custom debounce interval is honoured`() = runComposeUiTest {
        var input by mutableStateOf("x")
        var lastApplied = ""
        setContent {
            val applied = rememberDebouncedValue(input, delayMs = 1_000)
            lastApplied = applied
            Text(applied)
        }
        waitForIdle()

        input = "xy"
        mainClock.advanceTimeBy(999)
        waitForIdle()
        assertEquals("x", lastApplied, "1000ms 窗口内不生效")

        mainClock.advanceTimeBy(1)
        waitForIdle()
        assertEquals("xy", lastApplied)
    }
}
