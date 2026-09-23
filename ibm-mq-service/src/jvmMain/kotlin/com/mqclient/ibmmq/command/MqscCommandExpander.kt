package com.mqclient.ibmmq.command

/**
 * MQSC 命令缩写展开器（对齐 runmqsc 习惯）：把 `dis q(*)` 展开成
 * `DISPLAY QLOCAL(*)` 再送 PCF escape。
 *
 * 只处理**前两个词**：命令动词（第一词）与对象类型（第二词、取括号前的前缀，
 * 如 `q('X')` 只展开 `q`）——参数、值、引号内容一概不动。词法上不可能碰到
 * 字符串字面量：前两个词位于命令开头，引号只会出现在它们之后的参数区。
 *
 * 识别的词分两类：缩写（映射表）与全称（集合）——两者都规范化成大写；
 * 未知词原样保留（服务端对关键词大小写不敏感，不动最安全）。
 * 歧义按惯例取优先项：`RES` → RESET（RESUME 让用户写全称）。
 */
object MqscCommandExpander {

    /** 命令动词缩写 → 全称。 */
    private val verbAliases = mapOf(
        "DIS" to "DISPLAY",
        "DISP" to "DISPLAY",
        "DEF" to "DEFINE",
        "DEL" to "DELETE",
        "ALT" to "ALTER",
        "CLR" to "CLEAR",
        "STA" to "START",
        "STO" to "STOP",
        "SUS" to "SUSPEND",
        "RES" to "RESET",
        "REF" to "REFRESH",
        "MOV" to "MOVE",
        "REC" to "RECOVER",
        "ARC" to "ARCHIVE",
        "BAC" to "BACKUP",
    )

    /** 命令动词全称（无缩写也要规范化大写，如 PING / RESOLVE）。 */
    private val verbFullWords = setOf(
        "DISPLAY", "DEFINE", "DELETE", "ALTER", "CLEAR", "START", "STOP",
        "PING", "RESET", "REFRESH", "RESUME", "SUSPEND", "MOVE", "RECOVER",
        "RESOLVE", "ARCHIVE", "BACKUP",
    )

    /** 对象类型缩写 → 全称。 */
    private val objectAliases = mapOf(
        "Q" to "QLOCAL",
        "QL" to "QLOCAL",
        "QR" to "QREMOTE",
        "QA" to "QALIAS",
        "QM" to "QMODEL",
        "CHL" to "CHANNEL",
        "CH" to "CHANNEL",
        "T" to "TOPIC",
        "PRC" to "PROCESS",
        "NL" to "NAMELIST",
        "SRV" to "SERVICE",
        "AUTH" to "AUTHINFO",
        "COMM" to "COMMINFO",
    )

    /** 对象类型全称（含无缩写的 QMGR / SUB）。 */
    private val objectFullWords = setOf(
        "QLOCAL", "QREMOTE", "QALIAS", "QMODEL", "CHANNEL", "TOPIC", "SUB",
        "PROCESS", "NAMELIST", "SERVICE", "AUTHINFO", "COMMINFO", "QMGR",
    )

    /**
     * 展开一条命令的动词与对象类型缩写；注释（`*` 开头）与无法识别的词原样返回。
     *
     * 示例：`dis q('QUEUE') curdepth` → `DISPLAY QLOCAL('QUEUE') curdepth`
     * （第三个词起不动；服务端对关键词大小写不敏感，无需全句规范化）。
     */
    fun expand(command: String): String {
        val trimmedStart = command.trimStart()
        if (trimmedStart.startsWith("*")) return command
        val lead = command.length - trimmedStart.length
        var index = lead
        // 前导空白原样保留。
        val expanded = StringBuilder(command.substring(0, lead))

        // 第一词：命令动词。
        val verb = readWord(command, index) ?: return command
        expanded.append(canonical(verb, verbAliases, verbFullWords) ?: return command)
        index += verb.length

        // 词间空白保留，再读第二词。
        var wordStart = index
        while (wordStart < command.length && command[wordStart].isWhitespace()) wordStart++
        if (wordStart > index) {
            expanded.append(command.substring(index, wordStart))
            index = wordStart
        }

        // 第二词：对象类型（`q('X')` 形态时只取括号前缀，括号及之后原样保留）。
        if (index < command.length) {
            val obj = readWord(command, index)
            if (obj != null) {
                val parenAt = obj.indexOf('(')
                val head = if (parenAt >= 0) obj.substring(0, parenAt) else obj
                val tail = if (parenAt >= 0) obj.substring(parenAt) else ""
                val canonicalObj = canonical(head, objectAliases, objectFullWords)
                if (canonicalObj != null) {
                    expanded.append(canonicalObj).append(tail)
                    index += obj.length
                }
            }
        }

        expanded.append(command.substring(index))
        return expanded.toString()
    }

    /** 从 [start] 起读一个词（到空白为止）；无词返回 null。 */
    private fun readWord(text: String, start: Int): String? {
        var end = start
        while (end < text.length && !text[end].isWhitespace()) end++
        return if (end > start) text.substring(start, end) else null
    }

    /** 大小写不敏感查表：命中缩写或全称返回规范化大写，未知返回 null（原样保留）。 */
    private fun canonical(word: String, aliases: Map<String, String>, fullWords: Set<String>): String? {
        val upper = word.uppercase()
        return aliases[upper] ?: if (upper in fullWords) upper else null
    }
}
