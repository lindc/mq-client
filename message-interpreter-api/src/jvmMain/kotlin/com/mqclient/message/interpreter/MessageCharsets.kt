package com.mqclient.message.interpreter

import java.nio.charset.Charset

/**
 * CCSID → Java Charset（消息解释器的公共工具）：文本类解释器与 UI 渲染共用，
 * 保证「同一份字节，列表、详情、各解释器解出的文本一致」。
 *
 * 常用子集；查不到的 CCSID 由调用方决定回退策略（约定回退 UTF-8）。
 */
object MessageCharsets {

    /** CCSID → [Charset]；未知 CCSID 返回 null（不猜）。 */
    fun charsetForOrNull(ccsid: Int): Charset? = when (ccsid) {
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

    /**
     * 消息体 → 展示文本的默认解码：按 CCSID 选字符集，未知回退 UTF-8；
     * 非法字节序列用替换符（不抛异常）。空字节安全。
     */
    fun decode(content: ByteArray, codedCharSetId: Int): String {
        if (content.isEmpty()) return ""
        val charset = charsetForOrNull(codedCharSetId) ?: Charsets.UTF_8
        return String(content, charset)
    }
}
