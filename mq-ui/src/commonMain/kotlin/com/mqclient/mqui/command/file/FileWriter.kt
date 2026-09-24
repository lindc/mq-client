package com.mqclient.mqui.command.file

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.writeString

/**
 * 把执行结果文本写入用户选择的文件。writeString 是 suspend，不阻塞 EDT。
 * 独立成扩展是为了给「保存结果」一个与 [readCommandText] 对称的出口 ——
 * 失败以异常上抛，由调用方翻译提示。
 */
suspend fun PlatformFile.writeResultText(content: String) {
    writeString(content)
}
