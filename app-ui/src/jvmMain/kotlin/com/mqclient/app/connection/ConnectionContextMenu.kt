package com.mqclient.app.connection

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import com.mqclient.core.ConnectionActions
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ContextMenuItem
import com.mqclient.core.CoreModule
import com.mqclient.core.DefaultConnectionMenu
import com.mqclient.core.ServiceAdapter
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.launch

/**
 * 连接右键菜单的宿主渲染：模块经 `ServiceAdapter.contextMenuItems` 提供纯数据
 * 菜单项（见 [ContextMenuItem]），这里统一转成 Material 3 的 `DropdownMenu`。
 *
 * 宿主对模块菜单做的全部「再加工」：
 *  1. 过滤连续 / 首尾分隔线（[filterNotConsecutiveSeparators]）；
 *  2. 模块没注册 `ConnectionPropertiesUi` 时去掉「查看队列管理器属性」项 ——
 *     按固定 id [DefaultConnectionMenu.ITEM_QMGR_PROPERTIES] 识别，自定义菜单
 *     用同一个 id 即可享受同一开关；
 *  3. 文案经 i18n 解析、危险操作用 error 色、点击回调在协程里跑并兜住异常。
 *
 * 必须放在被右键的那一行 `Box` **内部**：`DropdownMenu` 锚定父布局节点。
 */
@Composable
fun ConnectionContextMenu(
    connection: ConnectionInfo,
    adapter: ServiceAdapter,
    actions: ConnectionActions,
    hasPropertiesUi: Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // key 到 id + status：状态变（连接/断开）菜单项就要换文案，右键重新打开自然重算。
    val items = remember(connection.id, connection.status) {
        adapter.contextMenuItems(connection, actions)
            .let { list -> if (hasPropertiesUi) list else list.filterNot { it.id == DefaultConnectionMenu.ITEM_QMGR_PROPERTIES } }
            .filterNotConsecutiveSeparators()
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        items.forEach { item ->
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
                        scope.launch {
                            // 模块的回调失败统一上报错误通道（打包后控制台不可见，
                            // CoreModule.reportError 会走到界面的错误对话框）。
                            runCatching { item.onClick() }.onFailure { error ->
                                CoreModule.reportError(
                                    message = error.message ?: error::class.simpleName ?: "unknown",
                                    source = "app-ui",
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
 * 右键触发 + 菜单挂载的一体化容器：包住一行节点内容，右键按下时打开
 * [ConnectionContextMenu]（锚定本容器）。
 *
 * 右键**不改变选中**：选中是左键的语义，右键只该弹菜单（与桌面软件的惯例一致）。
 */
@Composable
fun ContextMenuBox(
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    menu: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.onSecondaryPress(onOpenMenu)) {
        content()
        if (menuOpen) menu()
    }
}

/** 监听次键（右键）按下；重复按下时 [onOpenMenu] 只会被再调一次（状态本身就是幂等的）。 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.onSecondaryPress(onSecondaryClick: () -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { event ->
        if (event.buttons.isSecondaryPressed) onSecondaryClick()
    }

/**
 * 过滤连续的分隔线，并去掉**开头与结尾**的分隔线 —— 菜单以分隔线开始 / 结束都是
 * 破相（首项之前不该有线）。抽成扩展函数是为了可测（纯列表变换）。
 */
fun List<ContextMenuItem>.filterNotConsecutiveSeparators(): List<ContextMenuItem> {
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
