package com.mqclient.ibmmq.ui.message

import com.mqclient.mq.base.InterpreterResult
import com.mqclient.mq.base.MessageInterpreter
import com.mqclient.mq.base.ParamDef
import com.mqclient.mq.base.ParamKind
import java.nio.charset.Charset
import java.util.Base64
import java.util.ServiceLoader

/* ------------------------------ 字符集 ------------------------------ */

/** CCSID → Java Charset（文本渲染 / 解码层用；常用子集，查不到回退 UTF-8）。 */
internal fun ccsidToCharsetOrNull(ccsid: Int): Charset? = when (ccsid) {
    1208 -> Charsets.UTF_8
    819 -> Charsets.ISO_8859_1
    1252 -> Charset.forName("windows-1252")
    1200, 13488 -> Charsets.UTF_16BE
    936, 1381 -> Charset.forName("GBK")
    1386, 5488 -> Charset.forName("GB18030")
    37 -> Charset.forName("IBM037")
    500 -> Charset.forName("IBM500")
    1047 -> Charset.forName("IBM1047")
    437 -> Charset.forName("IBM437")
    850 -> Charset.forName("IBM850")
    else -> null
}

/** 消息体 → 展示文本的默认解码：按 CCSID，未知回退 UTF-8（非法序列用替换符不抛）。 */
internal fun decodeBody(content: ByteArray, codedCharSetId: Int): String {
    if (content.isEmpty()) return ""
    val charset = ccsidToCharsetOrNull(codedCharSetId) ?: Charsets.UTF_8
    return String(content, charset)
}

/* --------------------------- SPI 装载 --------------------------- */

/**
 * 解释器注册表：ServiceLoader 装载。装载对单个坏实现容错（构造 / 遍历失败跳过
 * 该实现，不拖垮整批 —— ModuleRegistry 同款约定）。
 *
 * 注意：实现类必须是 **public class 且带 public 无参构造**（ServiceLoader 反射
 * 实例化；Kotlin `object` 的构造器是 private，注册不了）。无状态实现，实例随意
 * 创建，等价单例。
 */
object MessageInterpreters {

    /** ServiceLoader 装载到的全部实现（内置与第三方同一条路），按注册顺序。 */
    fun loadAll(): List<MessageInterpreter> = runCatching {
        ServiceLoader.load(MessageInterpreter::class.java).iterator().asSequence().toList()
    }.getOrElse { emptyList() }

    /** 按 id 查找（恢复选中项用；找不到回落文本解释器）。 */
    fun byId(id: String): MessageInterpreter =
        loadAll().firstOrNull { it.id == id } ?: TextInterpreter()
}

/** 内容解码为 UTF-8 文本：JSON / XML / Base64 探测的公共入口。 */
private fun decodeAsUtf8(content: ByteArray): String =
    String(content, Charsets.UTF_8)

/** 文本：按 MQMD CodedCharSetId 解码原始字节，原样展示。默认解释器（首位注册）。 */
class TextInterpreter : MessageInterpreter {
    override val id = "text"
    override val displayName = "msg_interp_text"
    override val defaultParams: Map<String, String> = emptyMap()
    override val paramDefs: List<ParamDef> = emptyList()

    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean = false

    override fun interpret(content: ByteArray, encoding: Int, codedCharSetId: Int, params: Map<String, String>): InterpreterResult =
        InterpreterResult.Success(decodeBody(content, codedCharSetId))
}

/** JSON：解析 + 按缩进重排；解析失败如实报错。 */
class JsonInterpreter : MessageInterpreter {
    override val id = "json"
    override val displayName = "msg_interp_json"
    override val defaultParams = mapOf(PARAM_INDENT to "2")
    override val paramDefs = listOf(ParamDef(PARAM_INDENT, "msg_interp_param_indent", ParamKind.NUMBER))

    private companion object {
        const val PARAM_INDENT = "indent"
    }

    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean {
        val first = decodeAsUtf8(content).trimStart().firstOrNull() ?: return false
        return first == '{' || first == '['
    }

    override fun interpret(content: ByteArray, encoding: Int, codedCharSetId: Int, params: Map<String, String>): InterpreterResult {
        val text = decodeBody(content, codedCharSetId)
        return try {
            val indent = params[PARAM_INDENT]?.toIntOrNull()?.coerceIn(0, 8) ?: 2
            InterpreterResult.Success(prettyPrintJson(text, indent))
        } catch (e: Exception) {
            InterpreterResult.Failure(e.message ?: "JSON parse failed")
        }
    }

    /** 轻量 JSON 重排（不引入第三方依赖）：逐 token 换行 + 缩进；字符串字面量整体跳过。 */
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

/** XML：标签级缩进重排（可选）。 */
class XmlInterpreter : MessageInterpreter {
    override val id = "xml"
    override val displayName = "msg_interp_xml"
    override val defaultParams = mapOf(PARAM_PRETTY to "true")
    override val paramDefs = listOf(ParamDef(PARAM_PRETTY, "msg_interp_param_pretty", ParamKind.BOOLEAN))

    private companion object {
        const val PARAM_PRETTY = "pretty"
    }

    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean =
        decodeAsUtf8(content).trimStart().startsWith("<")

    override fun interpret(content: ByteArray, encoding: Int, codedCharSetId: Int, params: Map<String, String>): InterpreterResult {
        val text = decodeBody(content, codedCharSetId).trim()
        if (!text.startsWith("<")) return InterpreterResult.Failure("not XML")
        if (params[PARAM_PRETTY] == "false") return InterpreterResult.Success(text)
        return try {
            InterpreterResult.Success(prettyPrintXml(text))
        } catch (e: Exception) {
            InterpreterResult.Failure(e.message ?: "XML parse failed")
        }
    }

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

/** Base64：解码层 —— 解码字节后再交给内容解释器链（JSON → XML → 文本）。 */
class Base64Interpreter : MessageInterpreter {
    override val id = "base64"
    override val displayName = "msg_interp_base64"
    override val defaultParams: Map<String, String> = emptyMap()
    override val paramDefs: List<ParamDef> = emptyList()

    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean {
        val text = decodeAsUtf8(content).trim()
        if (text.isEmpty() || text.length % 4 != 0) return false
        return text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
    }

    override fun interpret(content: ByteArray, encoding: Int, codedCharSetId: Int, params: Map<String, String>): InterpreterResult {
        val text = decodeAsUtf8(content).trim()
        val decoded = try {
            Base64.getMimeDecoder().decode(text)
        } catch (e: IllegalArgumentException) {
            return InterpreterResult.Failure("Base64 decode failed: ${e.message}")
        }
        // 解码后的字节先探测 JSON / XML，都命中不了按文本展示（解码层不做强制判定）。
        val json = JsonInterpreter()
        val xml = XmlInterpreter()
        val chained = listOfNotNull(
            json.takeIf { it.canInterpret(decoded, encoding, 1208) },
            xml.takeIf { it.canInterpret(decoded, encoding, 1208) },
        ).firstOrNull()
        return if (chained != null) {
            when (val inner = chained.interpret(decoded, encoding, 1208, chained.defaultParams)) {
                is InterpreterResult.Success -> InterpreterResult.Success("Base64 → ${inner.formatted}")
                is InterpreterResult.Failure -> InterpreterResult.Failure(inner.message)
            }
        } else {
            InterpreterResult.Success(decodeBody(decoded, 1208))
        }
    }
}

/**
 * SAP IDoc：按行切分 + 段名标注展示（轻量示例实现，验证 SPI 机制；完整 IDoc
 * 解析不在范围）。参数：字符集名（默认 UTF-8）。
 */
class IdocInterpreter : MessageInterpreter {
    override val id = "idoc"
    override val displayName = "msg_interp_idoc"
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

    override fun interpret(content: ByteArray, encoding: Int, codedCharSetId: Int, params: Map<String, String>): InterpreterResult {
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
}
