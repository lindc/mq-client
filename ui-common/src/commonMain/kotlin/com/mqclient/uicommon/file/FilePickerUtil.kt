package com.mqclient.uicommon.file

/**
 * 本地文件选择：让用户从系统对话框里挑一个文件，返回选中文件的**绝对路径**；
 * 用户取消（或窗口关闭）返回 null。
 *
 * 放在 ui-common 而不是某个业务模块：各 MQ 模块都需要它（证书路径、消息体
 * 导入导出等），且实现与中间件无关 —— 只认路径与字节。
 *
 * [extensions] 是不含点的扩展名清单（如 `["jks", "p12"]`），用于对话框的文件
 * 过滤；传空清单表示不过滤。
 *
 * **suspend 契约**：实现内部保证不阻塞调用线程 —— JVM 端走 FileKit 的
 * `openFilePicker`（线程与模态由它内部处理），EDT（Compose 的 UI 线程）保持
 * 可响应；调用方（UI 点击）直接在协程里 await 即可。
 */
expect suspend fun pickFile(title: String, extensions: List<String>): String?

/**
 * 本地文件**多选**：返回全部选中文件的绝对路径；用户取消返回空清单。
 * 与 [pickFile] 同一套实现约定（suspend、不阻塞 UI 线程、异常吞掉按取消处理）。
 */
expect suspend fun pickFiles(title: String): List<String>

/**
 * 保存文件对话框：弹出系统「另存为」，[contents] 写入选定路径；返回保存路径，
 * 取消返回 null。
 *
 * 写盘失败以异常抛出（与「取消」区分，调用方据此给用户错误原因）；对话框自身
 * 打不开（headless 等）仍按取消返回 null。文件写入在本函数内完成（IO 线程由
 * FileKit 内部处理）。
 */
expect suspend fun saveFileBytes(
    suggestedName: String,
    defaultExtension: String,
    contents: ByteArray,
): String?
