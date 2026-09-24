package com.mqclient.mqui.command.file

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** 打开文件大小上限：10MB。 */
private const val MAX_OPEN_FILE_BYTES = 10 * 1024 * 1024

/**
 * 读取用户选择的命令文件为文本：校验大小上限与**严格 UTF-8** 可解码性。
 *
 * 失败以异常上抛，由调用方（Panel）翻译成用户可见的提示：
 *  - 超限 → [IllegalArgumentException]；
 *  - 非法 UTF-8 → [CharacterCodingException]；
 *  - 其余 IO 问题 → 原样上抛。
 *
 * readBytes 是 suspend，不阻塞 EDT。
 */
suspend fun PlatformFile.readCommandText(): String {
    val bytes = readBytes()
    // 消息只带尺寸明细（用户可见文案由调用方按 i18n 键拼装）。
    require(bytes.size <= MAX_OPEN_FILE_BYTES) {
        "约 ${bytes.size / 1024}KB，上限 ${MAX_OPEN_FILE_BYTES / 1024}KB"
    }
    val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(bytes)).toString()
}
