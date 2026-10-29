package com.mqclient.message.interpreter

/**
 * 消息列表预览文本的生成（各 MQ 模块的 Key / Value 单元格共用）。
 *
 * ## 为什么需要这个函数
 *
 * 现场（Kafka 消息列表）：同一个 Topic 里两条内容相近的 XML 消息，一条 Value 列正常
 * 显示 `<wpf:ResourceDictionary…`，另一条**整格空白**，看起来像没读到数据。
 *
 * 根因不在解码、也不在二进制判定，而在「首行没有可见字形」：列表单元格是
 * `Text(maxLines = 1, overflow = Ellipsis)`，只渲染**第一行**。原来的预览是
 * `String(bytes, 0, 256, UTF_8)` 原样返回 —— 只要正文以 `\n` / `\r\n` / 若干空格 /
 * UTF-8 BOM（`\uFEFF`，.NET 侧生成的 XML 常见）开头，第一行就是空的，单元格便一个
 * 像素都不画（实测：`"\n\n<wpf:…"` 渲染 ink=0，去掉前导换行后 ink=1119）。用户看到
 * 的是「这条消息没内容」，而不是「这条消息以空行开头」。
 *
 * 三个直觉上的怀疑方向为什么都不是主因，记下来免得下次又往那边找：
 *  - **二进制判定**：`isProbablyBinary` 命中时 UI 走 hex 分支，hex 一定非空 ——
 *    那一路只会显示十六进制，不会留白；
 *  - **非 UTF-8 解码**：容错解码把非法字节变成 `\uFFFD`，替换符是**有字形**的，
 *    显示为 `���` 而非空白（替换符占比 > 5% 时还会被判成二进制走 hex）；
 *  - **零长度**：0 字节确实解出空串，但它只是「整格空白」的**另一个**来源，由 UI
 *    侧的占位文案兜底，不该由预览函数用假文本冒充。
 *
 * 所以这里只做一件事：把预览压成**一行有可视内容的文本**。它不负责编造占位文案 ——
 * 占位属于 UI 的表达（要按语言翻译），这一层不该产出面向人的字符串。
 *
 * 放在 message-interpreter-api 而不是某个 `*-service`：Kafka 与 RocketMQ 的
 * `previewOf` 曾是逐字相同的两份副本，同一个盲区要修两遍；`isProbablyBinary` /
 * `hexPreview` 已经住在这里，字节展示口径的统一该由同一处承载。
 */

/** UTF-8 容错解码（非法字节出替换符）+ 截断到 [maxBytes] 字节 + 压成单行。 */
fun previewTextOf(bytes: ByteArray, maxBytes: Int = DEFAULT_PREVIEW_BYTES): String {
    val end = minOf(bytes.size, maxBytes)
    return singleLinePreview(String(bytes, 0, end, Charsets.UTF_8))
}

/** 预览默认截断字节数（各模块列表共用同一口径）。 */
const val DEFAULT_PREVIEW_BYTES: Int = 256

/**
 * 把任意文本压成一行：丢弃 BOM 与零宽字符，控制字符 / 空白折叠为单个空格，首尾去空。
 *
 * 「折叠」而不是「删除」很重要：`"a\nb"` 应该读成 `a b`，直接删会拼成 `ab` ——
 * XML 里 `</a>\n<b>` 这类相邻标签会连成 `</a><b>`，看不出原来有换行。
 *
 * `\r\n` 与 `\n\n` 都折叠成一个空格（不是两个）：预览只有一行，多个换行没有信息量。
 */
fun singleLinePreview(raw: String): String {
    if (raw.isEmpty()) return ""
    val sb = StringBuilder(raw.length)
    var pendingSpace = false
    for (ch in raw) {
        when {
            // BOM 与零宽控制符：不可见，且不是「间隔」，直接丢。
            isZeroWidth(ch) -> Unit
            ch.isWhitespace() || ch.isISOControl() -> pendingSpace = true
            else -> {
                // 只在两个可见字符之间补一个空格 —— 自然实现首尾去空。
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.append(ch)
            }
        }
    }
    return sb.toString()
}

private fun isZeroWidth(ch: Char): Boolean = when (ch) {
    '\uFEFF', // BOM / ZERO WIDTH NO-BREAK SPACE
    '\u200B', // ZERO WIDTH SPACE
    '\u200C', // ZERO WIDTH NON-JOINER
    '\u200D', // ZERO WIDTH JOINER
    '\u2060', // WORD JOINER
    -> true

    else -> false
}
