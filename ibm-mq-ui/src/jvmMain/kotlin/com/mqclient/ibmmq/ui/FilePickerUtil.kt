package com.mqclient.ibmmq.ui

import com.mqclient.core.log.Log
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LOG_TAG = "ibm-mq-ui"

/**
 * Desktop 端实现：[JFileChooser]（扩展名过滤跨平台可用），在 [Dispatchers.IO]
 * 上打开 —— EDT（Compose 的 UI 线程）保持可响应。
 *
 * 两个坑的来龙去脉（都踩过）：
 *  - **不能在 EDT 上打开**：Swing 模态循环压在 Compose 的 EDT 上会和 Compose
 *    Desktop 的绘制管线死锁（整窗 "Not Responding"），这是已知问题。所以放到
 *    IO 线程 —— Swing 的「单线程规则」在这里是实用妥协：模态对话框从非 EDT
 *    线程打开时自泵事件循环，实际可用，也是 Compose Desktop 社区的标准
 *    workaround；
 *  - **不能用 native FileDialog**：它只认 Frame/Dialog 作为 owner，而 Compose
 *    的窗口体系是 Window —— 挂不可见临时 Frame 时，owner 不可见导致原生模态
 *    对话框永远不显示。
 *
 * owner 取点击瞬间的焦点窗口（必然是主窗口；[JFileChooser.showOpenDialog]
 * 接受任意 Component，Window 也行），保证对话框置顶。任何弹框异常都吞掉并
 * 记日志返回 null（用户视角 = 取消），不能让选择器把表单搞崩。
 */
actual suspend fun pickFile(title: String, extensions: List<String>): String? =
    withContext(Dispatchers.IO) {
        if (GraphicsEnvironment.isHeadless()) return@withContext null
        try {
            val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
            val chooser = JFileChooser()
            chooser.dialogTitle = title
            chooser.isMultiSelectionEnabled = false
            if (extensions.isNotEmpty()) {
                chooser.fileFilter = FileNameExtensionFilter(
                    extensions.joinToString(", ") { "*.$it" },
                    *extensions.toTypedArray(),
                )
            }
            // 阻塞到用户选完 / 取消；跑在 IO 线程，不影响 EDT。
            if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile.absolutePath
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.error(LOG_TAG, "file chooser failed: $t")
            null
        }
    }
