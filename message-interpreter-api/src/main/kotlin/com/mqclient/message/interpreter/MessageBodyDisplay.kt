package com.mqclient.message.interpreter

/**
 * 消息体**原始字节**的展示工具：判定「该不该交给解释器」与「给不出可读文本时
 * 怎么看」这两件事。与 [MessageCharsets] 同一层级 —— 都是解释器实现与各 MQ
 * 模块 UI 共用的纯函数，不含任何中间件或 UI 依赖。
 *
 * 放在 message-interpreter-api 而不是某个 `*-ui` 模块：需要它的是**每个**接了
 * 消息浏览的 MQ 模块，且判定口径必须一致 —— 同一份字节在不同模块里显示出
 * 「文本/乱码」两种结论是最难查的那类 bug。
 */

/**
 * 二进制内容判定：前 8KB 内含 NUL 字节，或按 CCSID 解码后替换符（�）占比 > 5%
 * —— 任一命中即判为二进制，正文显示 hex 预览而非解码乱码。
 */
fun isProbablyBinary(content: ByteArray, codedCharSetId: Int): Boolean {
    val scanEnd = minOf(content.size, 8192)
    for (i in 0 until scanEnd) {
        if (content[i] == 0.toByte()) return true
    }
    if (content.isEmpty()) return false
    val text = MessageCharsets.decode(content, codedCharSetId)
    if (text.isEmpty()) return false
    val replacements = text.count { it == '\uFFFD' }
    return replacements.toFloat() / text.length > 0.05f
}

/**
 * 十六进制预览：`偏移  hex×16  |ASCII|` 行格式（不可见字节显示 ·），只取前
 * [maxBytes] 字节，超出以 … 收尾。二进制内容的兜底展示，用户至少能看到
 * 字节层面的事实（魔数、结构）。
 */
fun hexPreview(content: ByteArray, maxBytes: Int = 1024): String {
    val end = minOf(content.size, maxBytes)
    val sb = StringBuilder()
    for (offset in 0 until end step 16) {
        val rowEnd = minOf(offset + 16, end)
        sb.append("%08X  ".format(offset))
        for (i in offset until rowEnd) {
            sb.append("%02X ".format(content[i]))
        }
        repeat(16 - (rowEnd - offset)) { sb.append("   ") }
        sb.append(" |")
        for (i in offset until rowEnd) {
            val b = content[i].toInt() and 0xFF
            sb.append(if (b in 0x20..0x7E) b.toChar() else '·')
        }
        sb.append('|').append('\n')
    }
    if (content.size > end) sb.append("…")
    return sb.toString()
}
