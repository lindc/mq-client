package com.mqclient.ibmmq.ui.message

import com.mqclient.core.log.Log
import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.ParamDef
import com.mqclient.message.interpreter.ParamKind
import java.nio.charset.Charset
import java.util.ServiceLoader

/** 探测用 UTF-8 解码（canInterpret 只看首字符 / ASCII 段名，字符集不影响结论）。 */
private fun decodeAsUtf8(content: ByteArray): String = String(content, Charsets.UTF_8)

/**
 * IBM MQ 内置解释器的驻留部分（Phase 1）：Text / Xml / Idoc 三类尚未拆成独立
 * 模块，暂留 ibm-mq-ui（与 JSON / Base64 独立模块同一条 ServiceLoader 注册路径，
 * 见本模块 resources/META-INF/services）。Phase 2 按 JSON / Base64 的 pattern 迁出。
 */
/* --------------------------- SPI 装载 --------------------------- */

private const val LOG_TAG = "ibm-mq-ui"

/**
 * 解释器注册表：ServiceLoader 装载 + 排序。
 *
 *  - 装载逐 provider 容错：单个实现缺失 / 构造失败只跳过并记日志，不拖垮整批
 *    （ModuleRegistry 同款约定）；枚举器本身坏掉（配置损坏）时**停**而不是继续
 *    —— 同一坏迭代器会反复抛同一个错，继续循环等于死循环；
 *  - 按 [MessageInterpreter.sortOrder] 升序稳定排序（同序保持加载顺序），
 *    跨 JAR 的 ServiceLoader 注册顺序不可依赖。
 */
object MessageInterpreters {

    /** ServiceLoader 装载到的全部实现（内置与第三方同一条路）。 */
    fun loadAll(): List<MessageInterpreter> {
        val out = mutableListOf<MessageInterpreter>()
        val iterator = ServiceLoader.load(MessageInterpreter::class.java).iterator()
        while (true) {
            val provider = try {
                if (!iterator.hasNext()) break
                iterator.next()
            } catch (t: Throwable) {
                // hasNext / next 都可能抛 ServiceConfigurationError（Error，不是 Exception）。
                Log.error(LOG_TAG, "failed to load a message interpreter provider, stopping enumeration: $t")
                break
            }
            runCatching { out += provider }.onFailure {
                Log.error(LOG_TAG, "failed to instantiate a message interpreter, skipping: $it")
            }
        }
        return out.sortedBy { it.sortOrder }
    }

    /** 按 id 查找（恢复选中项用；找不到回落文本解释器）。 */
    fun byId(id: String): MessageInterpreter =
        loadAll().firstOrNull { it.id == id } ?: TextInterpreter()
}

/** 文本：按 MQMD CodedCharSetId 解码原始字节，原样展示。默认解释器（首位）。 */
class TextInterpreter : MessageInterpreter {
    override val id = "text"
    override val displayName = "msg_interp_text"
    override val sortOrder = 10
    override val defaultParams: Map<String, String> = emptyMap()
    override val paramDefs: List<ParamDef> = emptyList()

    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean = false

    override fun interpret(
        content: ByteArray,
        encoding: Int,
        codedCharSetId: Int,
        params: Map<String, String>,
    ): InterpreterResult = InterpreterResult.Success(MessageCharsets.decode(content, codedCharSetId))
}

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
}
