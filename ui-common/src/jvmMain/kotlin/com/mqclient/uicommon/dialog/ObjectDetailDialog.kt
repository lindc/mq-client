package com.mqclient.uicommon.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import dev.nucleusframework.application.DecoratedDialog
import dev.nucleusframework.application.LocalNucleusWindow
import dev.nucleusframework.window.windowDragArea

/**
 * 对象详情对话框：外观与设置对话框（SettingsDialog）一致——
 * nucleus 自绘标题栏（[windowDragArea] 拖拽 + ✕ 关闭按钮）、底部操作区、
 * 窗口边缘原生 resize（`resizable = true`），min 尺寸经 [LocalNucleusWindow]
 * 的 `setMinimumSize` 落地（框架无 setMaximumSize，上限只钳制**初始**尺寸）。
 *
 * **尺寸统一由公共层决定**：[sizeClass] 选择语义档位（属性/消息用 CONTENT、
 * 表单用 FORM），实际 dp 由 [rememberStandardDialogSize] 按屏幕比例计算并钳到
 * [DialogSizeSpec] 的 min / 95% 屏幕上限；调用方不得再写死宽高。
 *
 * 必须用 nucleus 的 `DecoratedDialog` 而非 compose 的 `DialogWindow`：
 * 项目的窗口管线是 nucleus/Tao 自绘框架（主线程非 AWT EDT），compose 的
 * DialogWindow 会在非 EDT 上创建 SkiaLayer 而抛
 * `IllegalStateException: Method should be called from AWT event dispatch thread`（实测）。
 * ESC 也走关闭（对话框无系统标题栏，键盘出口得自己接——同 SettingsDialog）。
 *
 * **⚠️ 嵌套确认框必须声明在本外壳的 content lambda 内**：compose `Dialog`/`AlertDialog`
 * 在 Desktop 上是独立窗口，owner = 声明它所在的组合所属的窗口——声明为本外壳的
 * **兄弟节点**时挂到主窗口，会被本对话框窗口压住（实测：确认框在后面"看不见"）。
 * 正确范本：QueueMessagesDialog → MessageBrowseTab 的删除确认；反例（已修）：
 * KafkaGroupDetailDialog / KafkaTopicPropertiesDialog 曾把确认框声明在外层。
 */
@Composable
fun ObjectDetailDialog(
    title: String,
    onDismiss: () -> Unit,
    onCloseRequest: (() -> Unit)? = null,
    sizeClass: DialogSizeClass = DialogSizeClass.CONTENT,
    /**
     * 底部操作区内容；null（默认）保留单一「关闭」按钮，由调用方（如消息详情的
     * 「下载 | 删除 | 关闭」）提供时原样渲染、布局间隔自理。放在 [RowScope] 里，
     * 调用方可用 weight(1f) 做弹性占位。
     */
    footerActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // 关闭出口统一走 requestClose：调用方要拦截（如发送中确认）时传 onCloseRequest，
    // 由它自行决定何时真正调 onDismiss；不传则 ✕ / ESC 直接关。
    val requestClose = onCloseRequest ?: onDismiss
    // 真正「让窗口消失」的那一下。必须走 hide()（原生系统调用，不经过 Compose 帧管线），
    // 否则会卡在等帧上：现场「点 ✕ 没反应，拖一下窗口才关上」就是这条闩锁
    // （见 dismissWithWindow 的 KDoc 与 doc/compose-frame-latches.md）。
    //
    // 只用于**放行**动作（✕ / ESC / 默认关闭）；onCloseRequest 这种「可能拦截」的入口
    // 保持原样 —— 拦截意味着这次点击不该关窗，提前 hide 会关错。
    // 标准尺寸由公共层按屏幕比例统一计算（min / 95% 屏幕上限已内置）。
    val startSize = rememberStandardDialogSize(sizeClass)

    DecoratedDialog(
        onCloseRequest = requestClose,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                requestClose()
                true
            } else {
                false
            }
        },
        state = rememberDialogState(size = startSize),
        title = title,
        resizable = true,
    ) {
        // min 尺寸落地（框架无 setMaximumSize，max 只钳制初始尺寸）。
        // CONTENT 档（属性/消息/详情，含表格）用内容区下限 900dp 并按屏幕 95% 封顶：
        // 既保证用户拖拽缩放时表格不被拉变形（任务 4），又不会在小屏上把窗口撑出屏幕。
        // FORM 档（创建/绑定/转发等表单，无宽表格）保持统一 MIN_WIDTH —— 否则小屏上
        // 一个创建表单窗口会被 900dp 下限撑到占满 88% 屏宽，反而是回归。
        val nucleusWindow = LocalNucleusWindow.current
        val screenWidth = rememberScreenWidth()
        val windowMinWidth = if (sizeClass == DialogSizeClass.CONTENT) {
            computeWindowMinWidth(screenWidth)
        } else {
            DialogSizeSpec.MIN_WIDTH
        }
        LaunchedEffect(nucleusWindow, windowMinWidth) {
            nucleusWindow.setMinimumSize(DpSize(windowMinWidth, DialogSizeSpec.MIN_HEIGHT))
        }
        // 兜底：内容换了（标题变 = 打开的是另一个对象）就把窗口显示回来。
        //
        // 为什么需要：closeNow 只是 hide，真正的销毁等父组合重组（要一帧）。若那一帧
        // 迟迟不来而用户又打开了另一个对象，`detailMessage?.let{}` 会**原地更新**这个
        // 节点而不是销毁重建 —— 窗口就停在隐藏态，用户会觉得「打不开了」。
        // 这一行保证任何一种「新内容出现」都把窗口亮回来。
        LaunchedEffect(title) {
            runCatching { nucleusWindow.show() }
        }
        // 关闭动作：先同步 hide 掉原生窗口，再通知宿主（理由见 dismissWithWindow）。
        val closeNow = remember(nucleusWindow, requestClose) {
            {
                if (onCloseRequest == null) {
                    dismissWithWindow(hide = { nucleusWindow.hide() }, onDismiss = requestClose)
                } else {
                    requestClose()
                }
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. 标题栏（拖拽区 + 标题 + ✕），样式同 SettingsDialog.HeaderBar
                Row(
                    modifier = Modifier
                        .windowDragArea()
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        onClick = closeNow,
                        standardMinWidth = false, // ✕ 是图标按钮，不套「4 字宽」标准。
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider()

                // 2. 内容区（业务模块填充）
                // clipToBounds：内容溢出时只能被裁到本区域，**不得**画到上下两条
                // 分割线 / 底部按钮栏上。模块内部的布局错误应该表现为「底部被切」，
                // 而不是「分割线从 Loading 中间穿过」—— 后者会让人以为分割线归错了
                // 容器（实测就踩过：RabbitMQ 交换机属性对话框的固定高度栈溢出）。
                //
                // 宽度规范由外壳**强制**施加，调用方无法覆盖：全项目所有属性 / 消息 /
                // 表单对话框的内容区统一为 min(W, max(0.8W, 900dp)) 并水平居中
                // （见 [computeContentWidth]）。用 BoxWithConstraints 取实际可用宽度
                // 再夹到 W，小屏上不会因 widthIn(min=900dp) 撑出窗口被裁掉右侧。
                Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val contentWidth = computeContentWidth(maxWidth)
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(modifier = Modifier.width(contentWidth).fillMaxHeight()) {
                                content()
                            }
                        }
                    }
                }

                // 3. 底部操作区：调用方定制（footerActions）或默认单一「关闭」
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    // 按钮间 12dp 间距（内容区段落级间距同款）；单按钮时无可见影响。
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (footerActions != null) {
                        footerActions()
                    } else {
                        SecondaryButton(
                            onClick = closeNow,
                        ) { Text(stringResource("common_close")) }
                    }
                }
            }
        }
    }
}
