package com.mqclient.message.interpreter.json

import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.ParamDef
import com.mqclient.message.interpreter.ParamKind

/**
 * JSON 解释器：解析 + 按缩进重排。轻量 token 级实现（不引入第三方 JSON 库 ——
 * kotlinx.serialization 等会做值级解析重建，重排输出与缩进行为都会变；本实现
 * 只动空白结构，字符串字面量原样保留）。解析失败如实报 [InterpreterResult.Failure]。
 */
class JsonInterpreter : MessageInterpreter {

    override val id = "json"
    override val displayName = "msg_interp_json"
    override val sortOrder = 20
    override val defaultParams = mapOf(PARAM_INDENT to "2")
    override val paramDefs = listOf(ParamDef(PARAM_INDENT, "msg_interp_param_indent", ParamKind.NUMBER))

    private companion object {
        const val PARAM_INDENT = "indent"
    }

    /** 探测用 UTF-8 解码（canInterpret 只看首字符，字符集不影响结论）。 */
    private fun decodeAsUtf8(content: ByteArray): String = String(content, Charsets.UTF_8)

    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean {
        val first = decodeAsUtf8(content).trimStart().firstOrNull() ?: return false
        return first == '{' || first == '['
    }

    override fun interpret(
        content: ByteArray,
        encoding: Int,
        codedCharSetId: Int,
        params: Map<String, String>,
    ): InterpreterResult {
        val text = MessageCharsets.decode(content, codedCharSetId)
        return try {
            val indent = params[PARAM_INDENT]?.toIntOrNull()?.coerceIn(0, 8) ?: 2
            InterpreterResult.Success(prettyPrintJson(text, indent))
        } catch (e: Exception) {
            InterpreterResult.Failure(e.message ?: "JSON parse failed")
        }
    }

    /** 轻量 JSON 重排：逐 token 换行 + 缩进；字符串字面量整体跳过。 */
    private fun prettyPrintJson(text: String, indent: Int): String {
        val pad = " ".repeat(indent)
        val sb = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            when {
                inString -> {
                    sb.append(ch)
                    when {
                        escaped -> escaped = false
                        ch == '\\' -> escaped = true
                        ch == '"' -> inString = false
                    }
                }
                ch == '"' -> {
                    sb.append(ch); inString = true
                }
                ch == '{' || ch == '[' -> sb.append(ch).append('\n').append(pad.repeat(++depth))
                ch == '}' || ch == ']' -> sb.append('\n').append(pad.repeat(--depth)).append(ch)
                ch == ',' -> sb.append(ch).append('\n').append(pad.repeat(depth))
                ch == ':' -> sb.append(": ")
                !ch.isWhitespace() -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
