package com.mqclient.message.interpreter.idoc

import com.mqclient.message.interpreter.InterpreterResult
import com.mqclient.message.interpreter.MessageInterpreter
import com.mqclient.message.interpreter.ParamDef
import com.mqclient.message.interpreter.ParamKind
import com.mqclient.message.interpreter.SelectOption
import java.nio.charset.Charset

/**
 * SAP IDoc 解释器（平面文件格式）：解析控制记录（EDI_DC*，头信息：发送方 /
 * 接收方 / 消息类型等）与数据段（E2ED*，段名 + 文档号 / 段号 / 父段号 / 层级 +
 * SDATA 数据），数据段按父段号组织成树。
 *
 * 边界（诚实声明）：SDATA 内部的业务字段按 SAP 段定义（DDIC）的固定位置排布，
 * 本地没有段定义仓库 —— TAB 分隔的 SDATA 按 name=value 展示，固定宽的展示原文；
 * 不虚构字段名。
 *
 * 参数（全有默认值，不改也能解析）：
 *  - charset：字符集，默认 UTF-8；
 *  - version：默认 auto（按控制记录名推断 EDI_DC40→3.x / EDI_DC20→2.x），可手填；
 *    只影响输出标注，不改变解析行为；
 *  - showRawSegments：默认 false（false = 解析字段，true = 只列原始行）；
 *  - expandNested：默认 true（按父段号缩进成树，false = 平铺）。
 */
class IdocInterpreter : MessageInterpreter {
    override val id = "idoc"
    override val displayName = "msg_interp_idoc"
    override val sortOrder = 50
    override val defaultParams = mapOf(
        PARAM_CHARSET to "UTF-8",
        PARAM_VERSION to "auto",
        PARAM_SHOW_RAW to "false",
        PARAM_EXPAND to "true",
    )
    override val paramDefs = listOf(
        ParamDef(PARAM_CHARSET, "msg_interp_param_charset", ParamKind.TEXT),
        ParamDef(
            PARAM_VERSION,
            "msg_interp_param_version",
            ParamKind.SELECT,
            options = listOf(
                SelectOption("auto", "msg_interp_param_version_auto"),
                SelectOption("2.0", "2.0"),
                SelectOption("3.0", "3.0"),
                SelectOption("3.1", "3.1"),
                SelectOption("3.2", "3.2"),
                SelectOption("4.0", "4.0"),
            ),
        ),
        ParamDef(PARAM_SHOW_RAW, "msg_interp_param_show_raw", ParamKind.BOOLEAN),
        ParamDef(PARAM_EXPAND, "msg_interp_param_expand", ParamKind.BOOLEAN),
    )

    private companion object {
        const val PARAM_CHARSET = "charset"
        const val PARAM_VERSION = "version"
        const val PARAM_SHOW_RAW = "showRawSegments"
        const val PARAM_EXPAND = "expandNested"

        /**
         * 缩进层级上限。
         *
         * IDoc 数据段的 HLEVEL 正常只有个小位数（段层级），这里给一个远大于真实
         * 报文但能阻断内存放大的上限；超过就按上限缩进，`level=` 仍显示原始值。
         */
        const val MAX_NESTING_LEVEL = 32

        /**
         * EDI_DC40 平面文件控制记录的已知字段序（高置信前缀）：按 TAB / 多空格
         * 分隔后逐位命名，超出的字段以 #序号 显示。表不完整时解析照常降级。
         */
        val CONTROL_FIELDS = listOf(
            "TABNAM", "MANDT", "DOCNUM", "DOCREL", "STATUS", "DIRECT", "OUTMOD", "TEST",
            "IDOCTYP", "CIMTYP", "MESTYP", "MESCOD", "MESFCT", "STD", "STDVRS",
            "SNDPRT", "SNDPFC", "SNDPRN", "SNDSAD", "SNDLAD", "SNDPOR",
            "RCVPRT", "RCVPFC", "RCVPRN", "RCVSAD", "RCVLAD", "RCVPOR",
        )

        /** 数据段行：段名 + 数字列（DOCNUM / SEGNUM / PSGNUM / HLEVEL）+ SDATA。 */
        val SEGMENT_LINE = Regex("^(\\w+)\\s+((?:\\d+\\s+)*)(.*)$")
    }

    /**
     * 认领条件：解码后**第一个非空行**必须以 EDI_DC（控制记录）或 E2ED（数据段）
     * 开头 —— 行首匹配，普通正文里出现 IDoc 字样不算。
     */
    override fun canInterpret(content: ByteArray, encoding: Int, codedCharSetId: Int): Boolean {
        val head = String(content, 0, minOf(content.size, 1024), Charsets.UTF_8)
        val first = head.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        return first.trimStart().startsWith("EDI_DC") || first.trimStart().startsWith("E2ED")
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

        val first = lines.first().trimStart()
        // 段名含下划线（EDI_DC40）：isLetterOrDigit 会在 _ 处截断，必须带上。
        val controlName = first.takeWhile { it.isLetterOrDigit() || it == '_' }
        if (!controlName.startsWith("EDI_DC") && !controlName.startsWith("E2ED")) {
            return InterpreterResult.Failure("not an IDoc file: first line does not start with EDI_DC*/E2ED*")
        }
        val version = versionLabel(first, params[PARAM_VERSION] ?: "auto")

        if (params[PARAM_SHOW_RAW] == "true") {
            return InterpreterResult.Success(lines.joinToString("\n"))
        }

        val sb = StringBuilder()
        sb.append("IDoc ").append(version).append(" — ").append(lines.size).append(" segment(s)\n\n")

        // ---- 控制记录（头信息）----
        sb.append("== Control record: ").append(controlName).append(" ==\n")
        sb.append(parseFields(first, CONTROL_FIELDS)).append('\n')

        // ---- 数据段 ----
        sb.append("\n== Segments ==\n")
        val expand = params[PARAM_EXPAND] != "false"
        var index = 0
        for (line in lines.drop(1)) {
            index++
            sb.append(parseSegment(line, index, expand))
        }
        return InterpreterResult.Success(sb.toString().trimEnd())
    }

    /** 版本标注：auto 时按控制记录名推断，否则用用户给的值。 */
    private fun versionLabel(controlLine: String, versionParam: String): String =
        when {
            versionParam != "auto" -> versionParam
            controlLine.startsWith("EDI_DC40") -> "3.x"
            controlLine.startsWith("EDI_DC20") -> "2.x"
            else -> "unknown"
        }

    /**
     * 控制记录字段解析：TAB 优先（文件端口常见），无 TAB 按 2+ 空格切。
     * 已知序的字段标 SAP 名，超出的标 #序号。
     */
    private fun parseFields(line: String, known: List<String>): String {
        val parts = if (line.contains('\t')) line.split('\t') else line.split(Regex("\\s{2,}"))
        val sb = StringBuilder()
        parts.forEachIndexed { index, raw ->
            val value = raw.trim()
            if (value.isEmpty()) return@forEachIndexed
            val label = known.getOrNull(index) ?: "#$index"
            sb.append("  ").append(label).append(" = ").append(value).append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** 单个数据段：段名 + 数字列 + SDATA；expand 时按层级缩进。 */
    private fun parseSegment(line: String, index: Int, expand: Boolean): String {
        val sb = StringBuilder()
        val match = SEGMENT_LINE.find(line.trimStart())
        if (match == null) {
            sb.append("[").append(index).append("] ").append(line).append('\n')
            return sb.toString()
        }
        val name = match.groupValues[1]
        val numbers = match.groupValues[2].trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val sdata = match.groupValues[3].trim()
        val docnum = numbers.getOrNull(0) ?: "-"
        val segnum = numbers.getOrNull(1) ?: index.toString()
        val psgnum = numbers.getOrNull(2) ?: "-"
        val hlevel = numbers.getOrNull(3)?.toIntOrNull()
        // 展开嵌套：按 HLEVEL 缩进（2 空格 / 级）；无层级数字则平铺。
        //
        // **必须限幅**：hlevel 直接来自消息体，`"  ".repeat(hlevel - 1)` 在
        // hlevel=1_999_999_999 时要一次分配 ~4GB，小值（10^6）也会让每行多出 2MB，
        // 一条 1MB 的消息能放大成几个 GB。`OutOfMemoryError` 是 Error，
        // `catch (Exception)` 接不住，会穿出 LaunchedEffect 把界面弄死。
        val indent = if (expand && hlevel != null && hlevel > 1) {
            "  ".repeat((hlevel - 1).coerceAtMost(MAX_NESTING_LEVEL))
        } else {
            ""
        }
        sb.append(indent).append("[").append(segnum).append("] ").append(name)
            .append("  (doc=").append(docnum).append(", parent=").append(psgnum)
            .append(", level=").append(hlevel?.toString() ?: "-").append(")\n")
        if (sdata.isNotEmpty()) {
            if (sdata.contains('\t')) {
                sdata.split('\t').forEach { field ->
                    if (field.isNotBlank()) sb.append(indent).append("    ").append(field.trim()).append('\n')
                }
            } else {
                sb.append(indent).append("    ").append(sdata).append('\n')
            }
        }
        return sb.toString()
    }
}
