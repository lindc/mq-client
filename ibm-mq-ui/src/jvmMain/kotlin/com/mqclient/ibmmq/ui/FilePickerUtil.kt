package com.mqclient.ibmmq.ui

import com.mqclient.core.log.Log
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.path
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager

private const val LOG_TAG = "ibm-mq-ui"

/**
 * JVM 端实现：[FileKit] 的 `openFilePicker`（Windows 走 JNA 原生 IFileDialog，
 * 比旧的 JFileChooser 观感好；其内部自带线程处理，suspend 返回，EDT 不阻塞）。
 *
 *  - **parent**：取点击瞬间的焦点窗口（`FileKitDialogParent.awt`），保证对话框
 *    模态且置顶在主窗口上；取不到（无焦点）就以无 parent 打开；
 *  - **init**：FileKit 在 JVM 端要求先 `FileKit.init(appId)`，已在 `app-ui`
 *    的 `main()` 启动时统一初始化（appId = "mqclient"），这里不再重复；
 *  - 任何弹框异常都吞掉并记日志返回 null（用户视角 = 取消），不能让选择器把
 *    表单搞崩。
 */
actual suspend fun pickFile(title: String, extensions: List<String>): String? {
    if (GraphicsEnvironment.isHeadless()) return null
    return try {
        val parent = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow
            ?.let(FileKitDialogParent::awt)
        val settings = FileKitDialogSettings(title = title, parent = parent)
        // FileKitType.File(extensions)：扩展名不含点；空清单 = 不过滤（File() 的默认行为）。
        val type = if (extensions.isEmpty()) FileKitType.File() else FileKitType.File(extensions)
        FileKit.openFilePicker(type = type, dialogSettings = settings)?.path
    } catch (t: Throwable) {
        Log.error(LOG_TAG, "file picker failed: $t")
        null
    }
}
