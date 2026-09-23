package com.mqclient.uicommon

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.compositionLocalOf

/**
 * 应用级 Snackbar 宿主状态：在 [app-ui] 的 [AppRoot] 创建并通过
 * `CompositionLocalProvider` 下发（包在 `Scaffold(snackbarHost = …)` 里）。任意深层
 * Composable（如各业务模块的命令控制台）都能用
 * `LocalSnackbarHostState.current.showSnackbar(…)` 弹出提示，不必各自再包一层
 * `Scaffold`。类型推断为 `ProvidableCompositionLocal`，这样 `.provides(value)` 才可用
 * （`provides` 是 [androidx.compose.runtime.ProvidableCompositionLocal] 的成员）。
 *
 * 取用时若外层没 Provide 会直接抛错（见下方默认值），便于发现遗漏。
 */
val LocalSnackbarHostState = compositionLocalOf<SnackbarHostState> { error("LocalSnackbarHostState 未被提供：AppRoot 应包一层 CompositionLocalProvider") }
