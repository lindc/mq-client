package com.mqclient.ibmmq.messaging

/**
 * 浏览会话的**身份化游标**：记住「哪些消息已经交付给 UI」，而不是「交付过多少条」。
 *
 * ## 为什么不能用条数
 *
 * [MessageBrowseSession] 每次翻页都重新 `accessQueue` + `BROWSE_FIRST`（句柄级光标
 * 换句柄即丢），原实现靠 `browsedCount` 从头跳过 N 条来恢复位置。但队列内容会变：
 *
 *  - 用户在列表里删掉一条 → 队列短一条，仍跳过 N 条就会把**一条从未展示过的消息**
 *    一起跳过去，界面上无声地少一条（删 k 条漏 k 条）；
 *  - 优先级队列在两页之间插入一条 → 同一页可能重复交付同一 MessageId，而 UI 用
 *    MessageId 当 Compose key，会直接崩在 `Key was already used`。
 *
 * ## 语义
 *
 * 计数的是**已交付且仍在队列里的消息**（按 MessageId 的多重集）。翻页时从头扫描，
 * 遇到计数 > 0 的 Id 就跳过并消耗一次；其余照常交付。
 *
 *  - [onMessageRemoved]：用户删掉一条已展示的消息，计数减一。**不调用它**的话，
 *    计数会偏大，在「同一 Id 出现多次」的极端输入下才可能多跳一条；正常 MQ 生成的
 *    MessageId 唯一，不调也只是内存多记一条。
 *  - [reset]：刷新（重新从队首读）时清空。
 *
 * 只看 Id 不看内容/位置，所以消息被别的应用消费掉、队列重排都不影响正确性 ——
 * 最坏只是多扫几个条目，永远不会漏交付。
 */
internal class BrowseCursor {

    /** MessageId → 已交付且仍在队列里的条数（多重集：MQ 允许重复 MessageId）。 */
    private val delivered = mutableMapOf<String, Int>()

    /** 开始一次翻页扫描；扫描期间的「已跳过」只记在局部副本里，不污染持久状态。 */
    @Synchronized
    fun startScan(): Scan = Scan(delivered.toMutableMap())

    /** 记录一条真正交付给 UI 的消息。 */
    @Synchronized
    fun recordDelivered(messageId: String) {
        delivered[messageId] = (delivered[messageId] ?: 0) + 1
    }

    /** 用户消费掉了一条已展示的消息：从「仍在队列里」的账上减一。 */
    @Synchronized
    fun onMessageRemoved(messageId: String) {
        val count = delivered[messageId] ?: return
        if (count <= 1) delivered.remove(messageId) else delivered[messageId] = count - 1
    }

    /** 回到队首重新开始（刷新）。 */
    @Synchronized
    fun reset() {
        delivered.clear()
    }

    /** 当前登记的消息条数（调试 / 测试用）。 */
    @Synchronized
    fun trackedCount(): Int = delivered.values.sum()

    /** 单次翻页的扫描状态：消耗式跳过，只在本次扫描内生效。 */
    class Scan internal constructor(
        private val remaining: MutableMap<String, Int>,
    ) {
        /** true = 这个 Id 在本页之前已经交付过，应跳过。 */
        fun shouldSkip(messageId: String): Boolean {
            val count = remaining[messageId] ?: return false
            if (count <= 0) return false
            if (count == 1) remaining.remove(messageId) else remaining[messageId] = count - 1
            return true
        }
    }
}
