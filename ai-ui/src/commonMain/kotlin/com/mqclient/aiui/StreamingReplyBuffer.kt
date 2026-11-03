package com.mqclient.aiui

/**
 * 流式回复的**渲染节流缓冲**：把“每来一个 SSE 分片就改一次 UI”变成“最多每
 * [intervalMs] 刷新一次”，同时保证首片立刻可见、收尾时一个字都不丢。
 *
 * ## 为什么需要
 *
 * 旧实现每收到一个分片就 `content + chunk` 写回消息，`AiMessageBubble` 因此对整个
 * 已累积文本**重跑一遍 Markdown 解析**。按 n 个分片、最终长度 L 估算：
 *  - 字符串拼接累计 O(n·L) 次字符拷贝；
 *  - Markdown 解析 n 次，每次 O(L) 起（块级结构还要从头扫）。
 * 服务端按 token 吐字，一篇长回复就是几千次全量重解析 —— 面板越写越卡，最后
 * 几行肉眼可见地掉帧。
 *
 * ## 节流规则（不是纯 debounce）
 *
 * 纯 debounce（“静止 100ms 才重绘”）在持续高速吐字时会一直不重绘。所以用两条触发：
 *  1. **首片立即上屏**（[visibleLength] == 0 时不等间隔）—— 用户点发送后马上看到
 *     第一个字，避免“没反应”的错觉；
 *  2. 之后距上次刷新 ≥ [intervalMs] 就刷一次 —— 稳态下大约 10 fps，解析成本从
 *     “分片数 × O(L)”降到“秒数 × 10 × O(L)”。
 *
 * 流结束 / 取消 / 异常时必须调 `takeIfDue(force = true)`：用户点「停止」之前看到的
 * 那半截回复要保留（`AiPanelStateTest` 的取消用例断言的就是这条）。
 *
 * 纯数据类、无协程、无 Compose 依赖 —— 时钟可注入，所以能离线单测。
 */
internal class StreamingReplyBuffer(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val pending = StringBuilder()

    /** 已经交给 UI 的字符数；`== pending.length` 表示没有待刷内容。 */
    private var visibleLength = 0

    /** 上次刷新的时刻；0 = 还没刷过（首片走“立即上屏”分支）。 */
    private var lastFlushAt = 0L

    /** 累计收到的分片（诊断 / 测试用）。 */
    var receivedChunks: Int = 0
        private set

    /** 收下一个分片：只进缓冲，不碰 UI。 */
    fun append(chunk: String) {
        if (chunk.isEmpty()) return
        pending.append(chunk)
        receivedChunks++
    }

    /**
     * 到点则有待刷内容时返回**完整快照**（含已上屏部分），否则 null。
     *
     * @param force true = 无视间隔立即刷（流结束 / 取消 / 异常收尾）。
     */
    fun takeIfDue(force: Boolean = false): String? {
        if (visibleLength == pending.length) return null
        val now = clock()
        if (!force && lastFlushAt != 0L && now - lastFlushAt < intervalMs) return null
        lastFlushAt = now
        visibleLength = pending.length
        return pending.toString()
    }

    /** 当前累计文本（测试断言用；不改变节流状态）。 */
    fun currentText(): String = pending.toString()

    companion object {
        /** 默认刷新间隔：约 10 fps，肉眼看是连续输出，解析成本降低一到两个数量级。 */
        const val DEFAULT_INTERVAL_MS: Long = 100L
    }
}
