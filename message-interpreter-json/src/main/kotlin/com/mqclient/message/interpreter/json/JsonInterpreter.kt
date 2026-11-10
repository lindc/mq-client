package com.mqclient.message.interpreter.json

import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.ParamDef
import com.mqclient.message.interpreter.ParamKind
import kotlinx.serialization.json.Json

/**
 * JSON 解释器：**先校验、后重排**。
 *
 * 历史实现是纯 token 重排：只认 `{ } [ ] , : "` 这几个字符就动手，于是
 * `{"a":}`、`{"a":1,}`、`{` 这类畸形输入也会返回 `Success` —— 用户看到一段
 * “排版过了”的文本，误以为消息内容合法。这在排障场景里是主动误导。
 *
 * 现在分成两步：
 *  1. `kotlinx.serialization.json` 的严格解析器做**合法性校验**（拒绝尾逗号、
 *     未加引号的 key、`NaN`、多个根值、单引号字符串…）。它只解析、不参与输出。
 *  2. 校验通过后仍走原来的**字符级重排**：只动空白，字符串字面量整体跳过，
 *     不重建值 —— 所以 `1.0` 不会变成 `1.0`（Double）或 `1`，大整数不丢精度，
 *     `\u00e9` 不会被转成 `é`，键顺序原样保留。
 *
 * 为什么不在第 2 步直接用 `JsonElement.toString()`：那会经过值级重建，键序、数字
 * 形态、转义形式都会变 —— 消息体展示应当是「broker 说的字节」的可读版，而不是
 * 我们重新编码的版本。
 *
 * 解析失败如实报 [InterpreterResult.Failure]，UI 保留原文 + 提示本条。
 */
class JsonInterpreter : MessageInterpreter {

    override val id = "json"
    override val displayName = "msg_interp_json"
    override val sortOrder = 20
    override val defaultParams = mapOf(PARAM_INDENT to "2")
    override val paramDefs = listOf(ParamDef(PARAM_INDENT, "msg_interp_param_indent", ParamKind.NUMBER))

    private companion object {
        const val PARAM_INDENT = "indent"

        /**
         * 校验用解析器：**严格**（不开 `isLenient`，不用 `ignoreUnknownKeys` ——
         * 那两项是给“服务端多给字段”的宽容，放在这里等于把畸形输入放行）。
         */
        val strict = Json
    }

    /** 探测用 UTF-8 解码（canInterpret 只看首字符，字符集不影响结论）。 */
    private fun decodeAsUtf8(content: ByteArray): String = String(content, Charsets.UTF_8)

    /**
     * 只有「看起来像 JSON **且** 真的能解析」才认领。
     *
     * 收紧的理由：Base64 解码层（`Base64Interpreter`）按 `canInterpret` 链式挑选
     * 内容解释器。旧的「首字符是 `{`/`[`」会把一段 `{oops` 也认领过来，接着
     * `interpret` 失败，整条 Base64 链路就变成 Failure —— 明明解码出来的只是普通文本。
     */
    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean {
        val text = decodeAsUtf8(content).trimStart()
        val first = text.firstOrNull() ?: return false
        if (first != '{' && first != '[') return false
        return runCatching { strict.parseToJsonElement(text) }.isSuccess
    }

    override fun interpret(
        content: ByteArray,
        encoding: Int,
        codedCharSetId: Int,
        params: Map<String, String>,
    ): InterpreterResult {
        val text = MessageCharsets.decode(content, codedCharSetId)
        return try {
            validate(text)
            val indent = params[PARAM_INDENT]?.toIntOrNull()?.coerceIn(0, 8) ?: 2
            InterpreterResult.Success(prettyPrintJson(text, indent))
        } catch (e: Exception) {
            InterpreterResult.Failure(e.message ?: "JSON parse failed")
        }
    }

    /**
     * 严格校验：解析成功即合法；异常消息里带上位置（kotlinx 的 message 含
     * `at offset N`），用户能据此定位到消息体的哪一段。
     */
    private fun validate(text: String) {
        // 允许首尾空白（消息体常有尾随换行），但空内容直接判失败。
        if (text.isBlank()) throw IllegalArgumentException("JSON parse failed: 内容为空")
        try {
            strict.parseToJsonElement(text)
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON parse failed: ${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * 轻量 JSON 重排：逐 token 换行 + 缩进；字符串字面量整体跳过。
     *
     * 两个细节：
     *  - 空容器（`{}` / `[]`）保持紧凑，不拆成三行（`{\n\n}` 是一种视觉噪声）；
     *  - `depth` 做下界保护：校验已挡住不配对的括号，这里只是不让 `" ".repeat(-1)`
     *    这种实现级崩溃有机会发生。
     */
    private fun prettyPrintJson(text: String, indent: Int): String {
        val pad = " ".repeat(indent)
        val sb = StringBuilder(text.length + 64)
        var depth = 0
        var inString = false
        var escaped = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
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
                    sb.append(ch)
                    inString = true
                }
                ch == '{' || ch == '[' -> {
                    // 空容器：直接吞掉紧随其后的收尾括号。
                    val next = text.getOrNull(index + 1)
                    if (next != null && isClosingPair(ch, next)) {
                        sb.append(ch).append(next)
                        index++
                    } else {
                        depth++
                        sb.append(ch).append('\n').append(pad.repeat(depth))
                    }
                }
                ch == '}' || ch == ']' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    sb.append('\n').append(pad.repeat(depth)).append(ch)
                }
                ch == ',' -> sb.append(ch).append('\n').append(pad.repeat(depth))
                ch == ':' -> sb.append(": ")
                !ch.isWhitespace() -> sb.append(ch)
            }
            index++
        }
        return sb.toString()
    }

    private fun isClosingPair(open: Char, close: Char): Boolean =
        (open == '{' && close == '}') || (open == '[' && close == ']')

}
