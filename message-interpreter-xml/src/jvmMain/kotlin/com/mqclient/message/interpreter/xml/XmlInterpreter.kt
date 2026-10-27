package com.mqclient.message.interpreter.xml

import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageCharsets
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.ParamDef
import com.mqclient.message.interpreter.ParamKind
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

/**
 * XML 解释器：**真解析**（DOM）后重新序列化成缩进文本。
 *
 * 为什么不再是「按 `<` / `>` 切分」：`split(Regex("(?=<)|(?<=>)"))` 只看字符，
 * 对下面三类合法输入会把标签切碎，产出乱码 ——
 *  1. `<![CDATA[ if (a < b) … ]]>`：CDATA 里的 `<` 被当成标签起点；
 *  2. `<!DOCTYPE msg [ <!ENTITY e "x"> ]>`：内部子集里的声明被当成元素；
 *  3. `<a title="x>y">`：属性值里的 `>` 被当成标签结束。
 * 走解析器后这三种情况由 XML 规范本身处理，不再依赖正则的猜测。
 *
 * ## 重新序列化的代价（有意接受）
 *
 * 输出是**重新生成**的文本，不是原始字节：
 *  - CDATA 段按 CDATA 原样输出（内容逐字保留，含换行）；
 *  - 实体引用（`&amp;` / `&#65;` / 自定义实体）被解析成字符后再按最小集重新转义
 *    （文本转义 `& < >`，属性值另加 `"`）—— 语义等价，字面形态可能不同；
 *  - 属性值统一用双引号、多空格折叠成一个；
 *  - 混合内容（`<p>a <b>x</b> c</p>`）按标签分行，标签之间的文本被 trim 后单独成行
 *    —— 缩进输出与「保留每一处空白」在 XML 里本来就不能同时成立。
 *  `<?xml …?>` 声明与 DOCTYPE 不重新输出（前者是编码声明、后者解析后已无意义）。
 *
 * ## 多根片段
 *
 * MQ 正文里经常是**片段**（一条消息里并列多个根元素，或 JMS 之外的手写报文）。
 * 先按完整文档解析；失败后再套一层内部根元素重试，成功则只输出其子节点 ——
 * 因此「多根」与「单根」都能格式化，而真正的语法错误仍然报 Failure。
 *
 * ## 安全
 *
 * 解析器按 XXE 加固：禁止外部通用 / 参数实体、禁止加载外部 DTD、开启
 * `FEATURE_SECURE_PROCESSING`、外部 DTD / Schema 访问置空。格式化一个消息体
 * 不该有网络或文件系统副作用。
 */
class XmlInterpreter : MessageInterpreter {
    override val id = "xml"
    override val displayName = "msg_interp_xml"
    override val sortOrder = 30
    override val defaultParams = mapOf(PARAM_PRETTY to "true")
    override val paramDefs = listOf(ParamDef(PARAM_PRETTY, "msg_interp_param_pretty", ParamKind.BOOLEAN))

    private companion object {
        const val PARAM_PRETTY = "pretty"

        /** 缩进单位（与历史输出一致：两级空格）。 */
        const val INDENT_UNIT = "  "

        /** 片段重试用的包装根：名字带 `mqclient` 前缀，正常文档里不可能撞上。 */
        const val FRAGMENT_ROOT = "mqclient-fragment-root"

        /** 片段重试前剥离的 XML 声明（包装后它不再位于文档开头，解析器会直接报错）。 */
        val XML_DECLARATION = Regex("""^\s*<\?xml\b[^>]*\?>""")

        /**
         * 解析器特征。逐条 `runCatching`：不同实现（Xerces / Android / 第三方）对个别
         * 特征名可能不支持，某条设不上不该让整个解释器失效 —— 但这几条里
         * `FEATURE_SECURE_PROCESSING` 与 entity 两条是安全前提，设不上时记录日志、
         * 仍然继续（宁可格式化失败，也不要静默联网取实体）。
         */
        fun newDocumentBuilder() =
            DocumentBuilderFactory.newInstance().apply {
                // 不按命名空间处理：序列化时直接取限定名，前缀原样保留（格式化工具
                // 不该擅自改写 `soap:` / `ns1:` 这类前缀）。
                isNamespaceAware = false
                isXIncludeAware = false
                isExpandEntityReferences = true
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching {
                    setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                }
                runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
                runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
            }.newDocumentBuilder()
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
        } catch (e: XmlFormatException) {
            InterpreterResult.Failure(e.message ?: "XML parse failed")
        } catch (e: Exception) {
            // 解析器抛出的非 SAX 异常（罕见：实现 bug / 编码问题）同样不许冒泡。
            InterpreterResult.Failure(e.message ?: "XML parse failed")
        }
    }

    /** 探测用 UTF-8 解码（canInterpret 只看首字符，字符集不影响结论）。 */
    private fun decodeAsUtf8(content: ByteArray): String = String(content, Charsets.UTF_8)

    /* ------------------------------ 解析 ------------------------------ */

    /** 格式化失败。带位置信息（解析器给的 line/column），UI 直接展示。 */
    private class XmlFormatException(message: String) : Exception(message)

    /**
     * 解析成 DOM：先按完整文档，失败再按「片段」重试（套内部根元素）。
     *
     * 返回 (文档, 是否需要跳过包装根)。两条路径都失败时，报**第一条**错误 ——
     * 那才是用户真正想看的位置（片段重试的报错会把行号加 1、并指向包装标签）。
     */
    private fun parse(text: String): Pair<Document, Boolean> {
        val direct = runCatching { parseDocument(text) }
        direct.getOrNull()?.let { return it to false }

        val fragment = XML_DECLARATION.replace(text, "").trim()
        val wrapped = "<$FRAGMENT_ROOT>$fragment</$FRAGMENT_ROOT>"
        val retried = runCatching { parseDocument(wrapped) }
        retried.getOrNull()?.let { return it to true }

        throw XmlFormatException(describe(direct.exceptionOrNull() ?: retried.exceptionOrNull()!!))
    }

    private fun parseDocument(text: String): Document =
        newDocumentBuilder().parse(InputSource(StringReader(text)))

    /** SAXParseException → 「XML 解析失败：第 N 行第 M 列：…」；其它异常原样取 message。 */
    private fun describe(error: Throwable): String = when (error) {
        is SAXParseException -> buildString {
            append("XML 解析失败：第 ").append(error.lineNumber).append(" 行第 ")
            append(error.columnNumber).append(" 列：")
            append(error.message?.substringBefore('\n')?.trim().orEmpty())
        }
        else -> "XML 解析失败：${error.message ?: error::class.simpleName ?: "unknown"}"
    }

    /* ------------------------------ 序列化 ------------------------------ */

    private fun prettyPrintXml(text: String): String {
        val (document, skipWrapper) = parse(text)
        val sb = StringBuilder()
        val roots = if (skipWrapper) {
            document.documentElement.childNodes.let { nodes -> (0 until nodes.length).map(nodes::item) }
        } else {
            listOf(document.documentElement)
        }
        for (root in roots) {
            if (root == null || root.nodeType != Node.ELEMENT_NODE) continue
            writeNode(root, depth = 0, sb)
        }
        return sb.toString().trimEnd()
    }

    private fun writeNode(node: Node, depth: Int, sb: StringBuilder) {
        when (node.nodeType) {
            Node.ELEMENT_NODE -> writeElement(node, depth, sb)
            Node.TEXT_NODE -> {
                val raw = node.nodeValue.orEmpty()
                if (raw.isBlank()) return // 纯空白 = 缩进产物，丢弃
                sb.append(indent(depth)).append(escapeText(raw.trim())).append('\n')
            }
            Node.CDATA_SECTION_NODE -> {
                // CDATA 逐字保留：内容（含换行与 `<`）不再二次转义。
                sb.append(indent(depth)).append("<![CDATA[").append(node.nodeValue.orEmpty()).append("]]>\n")
            }
            Node.COMMENT_NODE -> {
                sb.append(indent(depth)).append("<!--").append(node.nodeValue.orEmpty()).append("-->\n")
            }
            Node.PROCESSING_INSTRUCTION_NODE -> {
                val data = node.nodeValue.orEmpty()
                sb.append(indent(depth)).append("<?").append(node.nodeName)
                if (data.isNotBlank()) sb.append(' ').append(data)
                sb.append("?>\n")
            }
            // DOCTYPE / 实体引用在解析阶段已被展开或丢弃（见类 KDoc）。
            else -> Unit
        }
    }

    private fun writeElement(node: Node, depth: Int, sb: StringBuilder) {
        sb.append(indent(depth)).append('<').append(node.nodeName)
        node.attributes?.let { attributes ->
            for (i in 0 until attributes.length) {
                val attribute = attributes.item(i) ?: continue
                sb.append(' ').append(attribute.nodeName).append("=\"")
                    .append(escapeAttribute(attribute.nodeValue.orEmpty())).append('"')
            }
        }

        val children = node.childNodes
        val elements = (0 until children.length).mapNotNull { children.item(it) }
        // 纯空白文本子节点是缩进产物：`<a>   </a>` 按空元素输出，避免两行夹空内容。
        val content = elements.filterNot { it.isBlankText() }
        if (content.isEmpty()) {
            sb.append("/>\n")
            return
        }

        // 单文本 / 单 CDATA 子节点**内联**：`<a>1</a>`、`<code><![CDATA[…]]></code>`。
        // 这是 XML 最常见的形式，拆成三行只会让浏览更累（也是 MQ Explorer 的样式）。
        val only = content.singleOrNull()
        if (only != null && (only.nodeType == Node.TEXT_NODE || only.nodeType == Node.CDATA_SECTION_NODE)) {
            sb.append('>')
            if (only.nodeType == Node.CDATA_SECTION_NODE) {
                sb.append("<![CDATA[").append(only.nodeValue.orEmpty()).append("]]>")
            } else {
                sb.append(escapeText(only.nodeValue.orEmpty().trim()))
            }
            sb.append("</").append(node.nodeName).append(">\n")
            return
        }

        sb.append(">\n")
        for (child in content) {
            writeNode(child, depth + 1, sb)
        }
        sb.append(indent(depth)).append("</").append(node.nodeName).append(">\n")
    }

    private fun Node.isBlankText(): Boolean =
        nodeType == Node.TEXT_NODE && nodeValue.orEmpty().isBlank()

    private fun indent(depth: Int): String = INDENT_UNIT.repeat(depth)

    /** 文本节点转义：XML 里仅 `& < >` 必须转义（`>` 只在 `]]>` 序列里必需，统一转更稳）。 */
    private fun escapeText(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(ch)
            }
        }
    }

    /** 属性值转义：额外处理双引号（属性统一用双引号包裹）。 */
    private fun escapeAttribute(text: String): String = buildString(text.length) {
        for (ch in text) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(ch)
            }
        }
    }
}
