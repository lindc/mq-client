package com.mqclient.ibmmq.messaging

/**
 * 浏览会话的**身份化游标**：记住「哪些消息已经交付给 UI」，以及**最后交付的那条是谁**。
 *
 * ## 为什么不能只用条数
 *
 * 浏览光标是句柄级的，换句柄即丢。用「已交付条数」从头跳过同样多条来恢复位置时，
 * 队列内容一变就会出错：
 *
 *  - 用户在列表里删掉一条 → 队列短一条，仍跳过 N 条就会把**一条从未展示过的消息**
 *    一起跳过去，界面上无声地少一条（删 k 条漏 k 条）；
 *  - 优先级队列在两页之间插入一条 → 同一页可能重复交付同一 MessageId，而 UI 用
 *    MessageId 当 Compose key，会直接崩在 `Key was already used`。
 *
 * ## 两个用途
 *
 * 1. **降级扫描**（[startScan]）：句柄重建后从队首扫时，按 Id 跳过已交付的条目。
 *    这是「按 Id 定位失败」时的兜底，正确但 O(已交付)。
 * 2. **重建锚点**（[lastLiveDeliveredId]）：句柄失效后重新打开时，用最后一条
 *    **仍在队列里**的已交付消息做 `MQGMO_BROWSE_FIRST + MQMO_MATCH_MSG_ID` 定位，
 *    一次 MQI 往返把光标放回原处 —— 这才是不依赖 O(n) 扫描的快路径。
 *
 * 锚点必须跳过已经被删除的尾部条目（用户删掉最后一条后，[lastLiveDeliveredId]
 * 要退到前一条还存在的那条），否则重建时的匹配必然失败、每次都退化成全量扫描。
 */
internal class BrowseCursor {

    /** MessageId → 已交付且仍在队列里的条数（多重集：MQ 允许重复 MessageId）。 */
    private val delivered = mutableMapOf<String, Int>()

    /**
     * 交付顺序（允许重复）。用于回答「最后一条还在队列里的已交付消息是哪条」——
     * 一张 map 没有顺序，`keys.last()` 不是交付顺序。
     */
    private val order = ArrayDeque<String>()

    /** 开始一次翻页扫描；扫描期间的「已跳过」只记在局部副本里，不污染持久状态。 */
    @Synchronized
    fun startScan(): Scan = Scan(delivered.toMutableMap())

    /** 记录一条真正交付给 UI 的消息。 */
    @Synchronized
    fun recordDelivered(messageId: String) {
        delivered[messageId] = (delivered[messageId] ?: 0) + 1
        order.addLast(messageId)
    }

    /** 用户消费掉了一条已展示的消息：从「仍在队列里」的账上减一。 */
    @Synchronized
    fun onMessageRemoved(messageId: String) {
        val count = delivered[messageId] ?: return
        if (count <= 1) delivered.remove(messageId) else delivered[messageId] = count - 1
        pruneDeadTail()
    }

    /**
     * 最后一条「已交付且仍在队列里」的 MessageId；一条都没有时返回 null。
     *
     * 重建浏览光标时以它为锚点（`MQMO_MATCH_MSG_ID`）。它同时也是「这个会话有没有
     * 交付过东西」的判据。
     */
    @Synchronized
    fun lastLiveDeliveredId(): String? {
        pruneDeadTail()
        return order.lastOrNull()
    }

    /** 回到队首重新开始（刷新）。 */
    @Synchronized
    fun reset() {
        delivered.clear()
        order.clear()
    }

    /** 当前登记的消息条数（调试 / 测试用）。 */
    @Synchronized
    fun trackedCount(): Int = delivered.values.sum()

    /** 尾部已被删除的条目不断退栈，保证 [order].last 永远指向一条仍然存在的消息。 */
    private fun pruneDeadTail() {
        while (true) {
            val tail = order.lastOrNull() ?: return
            if ((delivered[tail] ?: 0) > 0) return
            order.removeLast()
        }
    }

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
