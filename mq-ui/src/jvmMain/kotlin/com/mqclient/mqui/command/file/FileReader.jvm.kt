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

actual suspend fun PlatformFile.readCommandText(): String {
    val bytes = readBytes()
    // 消息只带尺寸明细（用户可见文案由调用方按 i18n 键拼装）。
    require(bytes.size <= MAX_OPEN_FILE_BYTES) {
        "约 ${bytes.size / 1024}KB，上限 ${MAX_OPEN_FILE_BYTES / 1024}KB"
    }
    val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        // 转成多平台异常，供 commonMain 调用方按 i18n 键区分「文件非 UTF-8」。
        throw FileNotUtf8Exception("not valid UTF-8: ${e.message}")
    }
}