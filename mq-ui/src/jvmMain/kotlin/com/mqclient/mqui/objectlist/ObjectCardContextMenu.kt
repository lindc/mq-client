package com.mqclient.mqui.objectlist

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpOffset
import com.mqclient.core.ContextMenuItem
import com.mqclient.core.CoreModule
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 对象卡片菜单的统一渲染（"..." 按钮与右键共用）：模块给纯数据菜单项
 * （[ContextMenuItem]），这里转成 Material 3 的 `DropdownMenu`。
 *
 * 与宿主连接右键菜单（app-ui 的 ConnectionContextMenu）同一套约定：
 *  - 分隔线渲染为 `HorizontalDivider`，并过滤连续 / 首尾的分隔线；
 *  - 文案经 i18n 解析，危险操作用 error 色；
 *  - 点击回调跑在**应用级 scope**（[CoreModule.scope]）—— 菜单随 onDismiss
 *    离开组合时 rememberCoroutineScope 会被取消，挂起动作会被
 *    ForgottenCoroutineScopeException 掐死在半路；应用级 scope 让菜单关不关
 *    与动作的生死无关。失败统一上报错误通道，模块不必自己 try/catch。
 */
@Composable
fun ObjectCardContextMenu(
    expanded: Boolean,
    items: List<ContextMenuItem>,
    onDismiss: () -> Unit,
    offset: DpOffset = DpOffset.Zero,
) {
    val filtered = remember(items) { items.filterNotConsecutiveSeparators() }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = offset) {
        filtered.forEach { item ->
            if (item.isSeparator) {
                HorizontalDivider()
            } else {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(item.title),
                            color =
                                if (item.isDangerous) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    enabled = item.enabled,
                    onClick = {
                        onDismiss()
                        CoreModule.scope.launch {
                            runCatching { item.onClick() }.onFailure { error ->
                                // 协程取消是正常控制流（应用退出），原样上抛。
                                if (error is CancellationException) throw error
                                CoreModule.reportError(
                                    message = error.message ?: error::class.simpleName ?: "unknown",
                                    source = "mq-ui",
                                    error = error,
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * 过滤连续的分隔线，并去掉开头与结尾的分隔线 —— 菜单以分隔线开始 / 结束都是破相
 * （与宿主 ConnectionContextMenu 的同名变换同一逻辑）。
 */
private fun List<ContextMenuItem>.filterNotConsecutiveSeparators(): List<ContextMenuItem> {
    val result = mutableListOf<ContextMenuItem>()
    var lastWasSeparator = true // 初始当「上一条是分隔线」，开头的分隔线自然被跳过。
    forEach { item ->
        if (item.isSeparator) {
            if (!lastWasSeparator) {
                result.add(item)
                lastWasSeparator = true
            }
        } else {
            result.add(item)
            lastWasSeparator = false
        }
    }
    while (result.isNotEmpty() && result.last().isSeparator) {
        result.removeAt(result.lastIndex)
    }
    return result
}
