package com.mqclient.starrocks.system

/**
 * FE 日志取数的**解析与决策**部分（纯函数，commonTest 直测）。
 *
 * ## 为什么日志只能是 HTTP
 *
 * 另外三个页面（backends / variables / ha）都有等价的 SQL，唯独「日志」不行：
 * `SHOW PROC '/log'` 在 3.4.0 报 `Unknown proc node path: /log`，FE 的日志文件
 * 只经 Web 端口暴露：`GET /log?file=fe.log`（Basic Auth，返回 HTML，正文在 `<pre>` 里）。
 *
 * 因此这里只放**不依赖网络**的那半：文件白名单、端口候选、HTML 提取。
 * 真正发请求的实现在 jvmMain 的 `StarRocksFeLogService`，测试用假实现替换。
 */

/** 可查看的 FE 日志文件（与 Web 页 `/log` 的文件参数一致）。 */
enum class FeLogFile(val fileName: String) {
    /** 主日志（INFO 及以上）。 */
    LOG("fe.log"),

    /** 只含 WARN / ERROR / FATAL，排障时信息密度最高。 */
    WARN("fe.warn.log"),

    /** 审计日志（慢查询 / 审计语句），受 `audit_log_modules` 控制。 */
    AUDIT("fe.audit.log"),
}

/**
 * FE HTTP 端口的候选序列。
 *
 * 三条来源，按可信度排序：
 *  1. `SHOW FRONTENDS` 的 `HttpPort` 列（最准 —— 集群里真实配置的端口）；
 *  2. 连接配置里显式给的 `httpPort`（用户知道自己在干什么时用）；
 *  3. `queryPort - 1000`：StarRocks 默认 `query_port=9030` / `http_port=8030`，
 *     两者恒差 1000。这是**兜底猜测**，所以排在最后并且要去重。
 *
 * 去重保序：同一端口试两次没有意义，反而多等一个超时。
 */
fun feHttpPortCandidates(discovered: Int?, configured: Int?, queryPort: Int?): List<Int> =
    listOfNotNull(
        discovered?.takeIf { it > 0 && it < 65_536 },
        configured?.takeIf { it > 0 && it < 65_536 },
        queryPort?.let { it - FE_HTTP_PORT_OFFSET }?.takeIf { it > 0 && it < 65_536 },
    ).distinct()

/** StarRocks 默认 query_port 与 http_port 的偏移（9030 → 8030）。 */
const val FE_HTTP_PORT_OFFSET: Int = 1000

/**
 * 从 FE Web 的 `/log` 响应里取出日志正文。
 *
 * 响应的正文在 `<pre>…</pre>` 里，且被 HTML 转义过（`&lt;` / `&gt;` / `&amp;` /
 * `&#39;`）。三件事必须一起做，少一件日志就会变样：
 *  1. 取第一个 `<pre>` 的内容（页面里还有导航栏等其它 HTML）；
 *  2. 反转义 HTML 实体；
 *  3. 保留换行（日志一行一条，压平就没法读了）。
 *
 * 找不到 `<pre>` 时返回经实体反转义的整段文本 —— 未来版本若改成纯文本响应，
 * 这里仍然能出内容，而不是空面板。
 */
fun extractLogText(html: String): String {
    val open = html.indexOf("<pre", ignoreCase = true)
    val body = if (open >= 0) {
        val afterTag = html.indexOf('>', open).takeIf { it >= 0 }
            ?: return unescapeHtml(html)
        val close = html.indexOf("</pre>", afterTag, ignoreCase = true)
        if (close > afterTag) html.substring(afterTag + 1, close) else html.substring(afterTag + 1)
    } else {
        html
    }
    return unescapeHtml(body).trim('\n')
}

/** HTML 实体反转义（只处理日志里真实会出现的几种；顺序不能颠倒）。 */
private fun unescapeHtml(text: String): String =
    text.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        // &amp; 必须最后转：否则 "&amp;lt;" 会先变成 "&lt;" 再被当成标签。
        .replace("&amp;", "&")

/**
 * 日志文本裁剪：只保留最后 [maxLines] 行。
 *
 * Web 页给的是整份文件（实测 fe.log 可取到两万多字节、还会随集群运行增长）。
 * 面板显示的是「最新发生了什么」，所以从尾部截取；截断时在最前面留一行说明，
 * 免得用户以为日志只有这么多。
 */
fun tailLines(text: String, maxLines: Int = 2000): String {
    if (maxLines <= 0) return ""
    val lines = text.lines()
    if (lines.size <= maxLines) return text
    val omitted = lines.size - maxLines
    return buildString {
        append("… ($omitted earlier lines omitted)\n")
        append(lines.subList(lines.size - maxLines, lines.size).joinToString("\n"))
    }
}
