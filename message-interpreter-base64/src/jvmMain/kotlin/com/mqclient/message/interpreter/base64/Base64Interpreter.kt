package com.mqclient.message.interpreter.base64

import com.mqclient.message.interpreter.DecodingInterpreter
import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreter
import java.util.Base64
import java.util.ServiceLoader

/**
 * Base64 解释器 —— **解码层**：先 Base64 解码字节，再交给内容解释器继续处理。
 *
 * 链式调用设计（模块互不依赖的前提下的解法）：
 *  - 本模块**只依赖 api**，不编译期引用 JSON / XML / 任何内容解释器；
 *  - 解码后经 [ServiceLoader] 运行时发现全部已注册的 [MessageInterpreter]
 *    （宿主 classpath 上有什么就有什么），按注册表的排序顺序用 canInterpret
 *    探测解码后的字节，第一个命中的接手渲染；全部不命中按 UTF-8 文本展示；
 *  - 探测时跳过一切 [DecodingInterpreter]（含自身）—— 防止 Base64 → Base64
 *    的递归；
 *  - 命中的内容解释器的输出加 `Base64 → ` 前缀，向用户交代两段式处理。
 *
 * 同一 app classpath 下 ServiceLoader 与宿主看到同一份注册表，无需宿主接线。
 */
class Base64Interpreter : DecodingInterpreter {

    override val id = "base64"
    override val displayName = "msg_interp_base64"
    override val sortOrder = 40

    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean {
        val text = decodeAsUtf8(content).trim()
        if (text.isEmpty() || text.length % 4 != 0) return false
        return text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    }

    override fun interpret(
        content: ByteArray,
        encoding: Int,
        codedCharSetId: Int,
        params: Map<String, String>,
    ): InterpreterResult {
        val text = decodeAsUtf8(content).trim()
        val decoded = try {
            Base64.getMimeDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            return InterpreterResult.Failure("Base64 decode failed: ${e.message}")
        }
        // 解码后的字节交给内容解释器（跳过解码层防递归）；全部不命中按文本展示。
        val chained = ServiceLoader.load(MessageInterpreter::class.java)
            .firstOrNull { it !is DecodingInterpreter && it.canInterpret(decoded, encoding, 1208) }
        return if (chained != null) {
            when (val inner = chained.interpret(decoded, encoding, 1208, chained.defaultParams)) {
                is InterpreterResult.Success -> InterpreterResult.Success("Base64 → ${inner.formatted}")
                is InterpreterResult.Failure -> InterpreterResult.Failure(inner.message)
            }
        } else {
            InterpreterResult.Success(MessageCharsets.decode(decoded, 1208))
        }
    }

    /** 探测用 UTF-8 解码（Base64 字母表是 ASCII 子集，字符集不影响结论）。 */
    private fun decodeAsUtf8(content: ByteArray): String = String(content, Charsets.UTF_8)
}
