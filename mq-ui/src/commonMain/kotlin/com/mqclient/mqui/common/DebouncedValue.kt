package com.mqclient.mqui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** 搜索框的默认防抖时长：与消息浏览页（手写 `delay(300)`）同一口径。 */
const val SEARCH_DEBOUNCE_MS: Long = 300L

/**
 * 输入值 → **防抖后的**生效值。
 *
 * 为什么需要：对象列表的过滤原本是「每敲一个键就在组合期过滤 + 重建卡片」。几百个队列 /
 * 通道时，快速输入会让每一帧都做一次 O(n) 过滤 + 列表重建，输入法合成的中间态还会
 * 触发一串无用重组（中文输入法尤其明显）。
 *
 * 用法（输入框仍绑原始值，过滤用生效值）：
 * ```
 * val applied = rememberDebouncedValue(searchText)
 * val filtered = items.filter { it.name.contains(applied, ignoreCase = true) }
 * ```
 *
 * 语义细节：
 *  - 首次进入立即生效（不延迟首屏）；
 *  - 值回到上次生效值（如清空输入）时同样要等一个窗口 —— 与「立即清空」的期望不符，
 *    所以这里特判 [immediateWhenEmpty]：清空是「取消筛选」这种明确动作，不该有滞后感。
 */
@Composable
fun rememberDebouncedValue(
    value: String,
    delayMs: Long = SEARCH_DEBOUNCE_MS,
    immediateWhenEmpty: Boolean = true,
): String {
    var applied by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value == applied) return@LaunchedEffect
        if (immediateWhenEmpty && value.isEmpty()) {
            applied = value
            return@LaunchedEffect
        }
        delay(delayMs)
        applied = value
    }
    return applied
}
