package com.mqclient.message.interpreter.xml

import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.ParamDef
import com.mqclient.message.interpreter.ParamKind

/** XML：标签级缩进重排（可选）。 */
class XmlInterpreter : MessageInterpreter {
    override val id = "xml"
    override val displayName = "msg_interp_xml"
    override val sortOrder = 30
    override val defaultParams = mapOf(PARAM_PRETTY to "true")
    override val paramDefs = listOf(ParamDef(PARAM_PRETTY, "msg_interp_param_pretty", ParamKind.BOOLEAN))

    private companion object {
        const val PARAM_PRETTY = "pretty"
    }

    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean =
        decodeAsUtf8(content).trimStart().startsWith("<")

    override fun interpret(
        content: ByteArray,
        encoding: Int,
        codedCharSetId: Int,
        params: Map<String, String>,
    ): InterpreterResult {
        val text = MessageCharsets.decode(content, codedCharSetId).trim()
        if (!text.startsWith("<")) return InterpreterResult.Failure("not XML")
        if (params[PARAM_PRETTY] == "false") return InterpreterResult.Success(text)
        return try {
            InterpreterResult.Success(prettyPrintXml(text))
        } catch (e: Exception) {
            InterpreterResult.Failure(e.message ?: "XML parse failed")
        }
    }

    /** 探测用 UTF-8 解码（canInterpret 只看首字符，字符集不影响结论）。 */
    private fun decodeAsUtf8(content: ByteArray): String = String(content, Charsets.UTF_8)

    /** 轻量 XML 缩进（标签级重排；不引入 XML 解析器依赖，标签间空白丢弃）。 */
    private fun prettyPrintXml(text: String): String {
        val tokens = text.split(Regex("(?=<)|(?<=>)")).filter { it.isNotBlank() }
        val sb = StringBuilder()
        var depth = 0
        for (token in tokens) {
            when {
                token.startsWith("<?") || token.startsWith("<!") -> {
                    sb.append("  ".repeat(depth)).appendLine(token)
                }
                token.startsWith("</") -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    sb.append("  ".repeat(depth)).appendLine(token)
                }
                token.startsWith("<") && token.endsWith("/>") -> {
                    sb.append("  ".repeat(depth)).appendLine(token)
                }
                token.startsWith("<") -> {
                    sb.append("  ".repeat(depth)).appendLine(token)
                    depth++
                }
                else -> {
                    // 文本节点：挂当前深度原样保留。
                    sb.append("  ".repeat(depth)).appendLine(token.trim())
                }
            }
        }
        return sb.toString().trimEnd()
    }
}
