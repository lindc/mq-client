package com.mqclient.message.interpreter.idoc

import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.ParamDef
import com.mqclient.message.interpreter.ParamKind
import java.nio.charset.Charset

/**
 * SAP IDoc：按行切分 + 段名标注展示（轻量示例实现，验证 SPI 机制；完整 IDoc
 * 解析不在范围）。参数：字符集名（默认 UTF-8）。
 */
class IdocInterpreter : MessageInterpreter {
    override val id = "idoc"
    override val displayName = "msg_interp_idoc"
    override val sortOrder = 50
    override val defaultParams = mapOf(PARAM_CHARSET to "UTF-8")
    override val paramDefs = listOf(ParamDef(PARAM_CHARSET, "msg_interp_param_charset", ParamKind.TEXT))

    private companion object {
        const val PARAM_CHARSET = "charset"
    }

    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean {
        val head = decodeAsUtf8(content.copyOfRange(0, minOf(content.size, 64)))
        // IDoc 数据记录常以 EDI_DC / E2ED 段名开头。
        return head.startsWith("EDI_DC") || head.startsWith("E2ED") || head.contains("EDI_DC40")
    }

    override fun interpret(
        content: ByteArray,
        encoding: Int,
        codedCharSetId: Int,
        params: Map<String, String>,
    ): InterpreterResult {
        val charset = try {
            Charset.forName(params[PARAM_CHARSET] ?: "UTF-8")
        } catch (e: Exception) {
            return InterpreterResult.Failure("unknown charset: ${params[PARAM_CHARSET]}")
        }
        val lines = String(content, charset).lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return InterpreterResult.Failure("empty IDoc")
        val sb = StringBuilder()
        lines.forEachIndexed { index, line ->
            val segmentName = line.take(8).trim().ifBlank { "?" }
            sb.append("---- [").append(index + 1).append("] ").append(segmentName)
                .append(" ----\n").append(line).append('\n')
        }
        return InterpreterResult.Success(sb.toString().trimEnd())
    }

    /** 探测用 UTF-8 解码（段名是 ASCII，字符集不影响结论）。 */
    private fun decodeAsUtf8(content: ByteArray): String = String(content, Charsets.UTF_8)
}
