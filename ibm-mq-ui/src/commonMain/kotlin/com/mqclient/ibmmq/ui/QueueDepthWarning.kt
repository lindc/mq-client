package com.mqclient.ibmmq.ui

/**
 * 「队列深度告警」的**唯一判定**（队列列表卡片与拓扑图节点着色共用）。
 *
 * 这条规则此前有两份实现：
 *  - `QueueCardAdapter.toQueueCardData`：`maxDepth > 0 && usageRatio >= threshold/100f`
 *  - `TopologyGraphBuilder.buildKuiverFromCache`：`ratio >= threshold/100f`，
 *    其中 maxDepth 未知时 ratio 取 0f
 *
 * 两者在正常阈值（50..100）下等价，但在 **threshold = 0** 时结论相反：拓扑那边
 * `0f >= 0f` 成立 → 把所有 maxDepth 未知（心跳先于慢扫建快照）的本地队列标成
 * WARNING，而列表页不标。表单校验把阈值限制在 50..100，所以这个分歧走 UI 摸不到；
 * 但 `ibmMQConfigFromOptions` 对 config.toml 的取值不做钳制 —— 手改配置就能触发。
 *
 * 同一个语义两处实现、且已经分叉，正是「抽出来 + 一份测试」要解决的事。
 *
 * @param currentDepth 当前深度（MQMD / PCF 缺失时按 0 处理）。
 * @param maxDepth 最大深度；**未知（null 或 <= 0）不算告警** —— 心跳快照可能先于
 *   慢扫建立，此时 maxDepth 还没有值，把「不知道上限」当成「已满」会全列表飘红。
 * @param thresholdPercent 告警阈值百分比（0..100）。
 */
internal fun isQueueDepthWarning(
    currentDepth: Int?,
    maxDepth: Int?,
    thresholdPercent: Int,
): Boolean {
    val max = maxDepth ?: return false
    if (max <= 0) return false
    val current = currentDepth ?: 0
    return current.toFloat() / max >= thresholdPercent / 100f
}
