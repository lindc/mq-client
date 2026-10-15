package com.mqclient.uicommon

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 统一的 Snackbar 宿主：**全项目 Snackbar 样式的唯一出处**。
 *
 * 为什么不用 Material3 默认样式：默认 `Snackbar` 用 `inverseSurface / inverseOnSurface`
 * 反色对，本主题（app-ui 的 MaterialKolor TonalSpot 动态 ColorScheme）浅色模式下这对
 * 反色对比度过低 —— 深字配近纯黑底，不可读。故固定用
 * `secondaryContainer / onSecondaryContainer` 配对色：浅色主题浅底深字、深色主题深底
 * 浅字；即使 contentColor 被内层忽略，回退到的 onSurface 也与该底色保持可读。
 *
 * 使用方式二选一：
 *  1. 面板私有宿主（弹在面板局部，如拓扑画布）：`ThemedSnackbarHost(hostState, Modifier)`；
 *  2. 宿主 Scaffold（`LocalSnackbarHostState` 分发的全局提示）：
 *     `Scaffold(snackbarHost = { ThemedSnackbarHost(it) })`。
 *
 * 触发逻辑（showSnackbar 的时机与文案）与本组件无关 —— 只管样式。
 */
@Composable
fun ThemedSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
