package com.mqclient.app.connection

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpOffset
import com.mqclient.core.ConnectionActions
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ContextMenuItem
import com.mqclient.core.CoreModule
import com.mqclient.core.DefaultConnectionMenu
import com.mqclient.core.ServiceAdapter
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException
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
 * [menuOffset] 是右键按下时光标相对所在行的位置 —— 没有它 `DropdownMenu` 会锚在
 * 整行的左上角，菜单永远出现在连接名字前面。
 *
 * 必须放在被右键的那一行 `Box` **内部**：`DropdownMenu` 锚定父布局节点。
 */
@Composable
fun ConnectionContextMenu(
    connection: ConnectionInfo,
    adapter: ServiceAdapter,
    actions: ConnectionActions,
    hasPropertiesUi: Boolean,
    menuOffset: DpOffset,
    onDismiss: () -> Unit,
) {
    // key 到 id + status：状态变（连接/断开）菜单项就要换文案，右键重新打开自然重算。
    val items = remember(connection.id, connection.status) {
        adapter.contextMenuItems(connection, actions)
            .let { list -> if (hasPropertiesUi) list else list.filterNot { it.id == DefaultConnectionMenu.ITEM_QMGR_PROPERTIES } }
            .filterNotConsecutiveSeparators()
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss, offset = menuOffset) {
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
                        // 菜单随 onDismiss 离开组合，rememberCoroutineScope 会跟着被
                        // 取消 —— 挂起动作（连接 / 查属性的先连接流程）会被
                        // ForgottenCoroutineScopeException 掐死在半路。动作跑在应用级
                        // scope 上：菜单关不关与动作的生死无关。
                        CoreModule.scope.launch {
                            // 模块的回调失败统一上报错误通道（打包后控制台不可见，
                            // CoreModule.reportError 会走到界面的错误对话框）。
                            runCatching { item.onClick() }.onFailure { error ->
                                // 协程取消是正常控制流（应用退出），原样上抛，
                                // 不许进错误通道吓用户。
                                if (error is CancellationException) throw error
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
 * 右键触发 + 菜单挂载的一体化容器：包住一行节点内容，右键按下时记录光标位置并
 * 打开 [menu]（锚定本容器，[menu] 收到的 [DpOffset] 就是光标相对本行的位置）。
 * 菜单的开关完全由本容器自管（`onDismiss` 由容器提供），外部无需维护任何状态。
 *
 * 右键**不改变选中**：选中是左键的语义，右键只该弹菜单（与桌面软件的惯例一致）。
 */
@Composable
fun ContextMenuBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    menu: @Composable (menuOffset: DpOffset, onDismiss: () -> Unit) -> Unit,
) {
    var openAt by remember { mutableStateOf<DpOffset?>(null) }
    val density = LocalDensity.current
    // M3 的 DropdownMenu 把菜单顶放在「锚点底边 + offset.y」—— 锚点是整行，
    // 所以 y 要倒扣行高，菜单顶才正好贴着光标（否则菜单比点击位置低一行）。
    var rowHeightPx by remember { mutableStateOf(0) }
    Box(
        modifier =
            modifier
                .onSizeChanged { rowHeightPx = it.height }
                .onSecondaryPress { position ->
                    openAt = with(density) {
                        DpOffset(position.x.toDp(), (position.y - rowHeightPx).toDp())
                    }
                },
    ) {
        content()
        openAt?.let { offset -> menu(offset) { openAt = null } }
    }
}

/** 监听次键（右键）按下，把按下点（本容器局部坐标）交给 [onSecondaryClick]。 */
@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.onSecondaryPress(onSecondaryClick: (position: androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    onPointerEvent(PointerEventType.Press) { event ->
        if (event.buttons.isSecondaryPressed) onSecondaryClick(event.changes.first().position)
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
