package com.mqclient.mqui.command.file

import com.mqclient.mqui.file.MAX_COMMAND_FILE_BYTES
import com.mqclient.mqui.file.MAX_COMMAND_FILE_KB
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.size
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

actual suspend fun PlatformFile.readCommandText(): String {
    // **先看尺寸再读**：`readBytes()` 会按文件长度一次性分配数组，先读后判的话
    // 「超过上限」要等整个文件已经进堆才抛得出来 —— 选错一个几百 MB 的日志文件就是
    // 一次 OOM（或长时间的 UI 冻结），而这条校验本来就是为了拦住它。
    val declared = size()
    require(declared <= MAX_COMMAND_FILE_BYTES) {
        "约 ${declared / 1024}KB，上限 ${MAX_COMMAND_FILE_KB}KB"
    }
    val bytes = readBytes()
    // stat 与 read 之间文件可能被替换（TOCTOU），真正进内存的字节数才是可信的。
    // 消息只带尺寸明细（用户可见文案由调用方按 i18n 键拼装）。
    require(bytes.size <= MAX_COMMAND_FILE_BYTES) {
        "约 ${bytes.size / 1024}KB，上限 ${MAX_COMMAND_FILE_KB}KB"
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
