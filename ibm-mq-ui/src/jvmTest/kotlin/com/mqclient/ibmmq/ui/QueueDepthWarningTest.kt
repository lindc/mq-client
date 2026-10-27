package com.mqclient.ibmmq.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 队列深度告警的**唯一判定**（队列列表卡片 + 拓扑图节点着色共用）。
 *
 * 抽这份之前它是两处实现：`QueueCardAdapter` 用 `maxDepth > 0 && ratio >= t/100`，
 * `TopologyGraphBuilder` 用 `ratio >= t/100`（未知 maxDepth 时 ratio=0）。正常阈值下
 * 等价，但 threshold=0 时拓扑会把所有 maxDepth 未知的本地队列标成 WARNING 而列表
 * 不标 —— 表单限制 50..100 所以 UI 摸不到，`ibmMQConfigFromOptions` 又不对
 * config.toml 钳制，手改配置就能触发。现在两边共用这一份。
 */
class QueueDepthWarningTest {

    @Test
    fun `threshold is inclusive`() {
        // 85% 阈值、100 上限：85 条即告警（含端点），84 条不告警。
        assertTrue(isQueueDepthWarning(85, 100, 85))
        assertFalse(isQueueDepthWarning(84, 100, 85))
    }

    @Test
    fun `unknown max depth never warns`() {
        // 心跳快照可能先于慢扫建立（maxDepth 还没值）——「不知道上限」不是「已满」。
        assertFalse(isQueueDepthWarning(1_000, null, 85))
        assertFalse(isQueueDepthWarning(1_000, 0, 85))
        assertFalse(isQueueDepthWarning(1_000, -1, 85))
    }

    @Test
    fun `zero threshold still does not warn on unknown max depth`() {
        // 这是两份旧实现分叉的那一格：手改 config.toml 设成 0 时，
        // 拓扑旧实现（0f >= 0f）会把未知上限的队列标红，列表旧实现不会。
        assertFalse(isQueueDepthWarning(0, null, 0), "未知上限 + 阈值 0：仍是「不知道」")
        assertFalse(isQueueDepthWarning(0, 0, 0))
    }

    @Test
    fun `null current depth counts as zero`() {
        assertFalse(isQueueDepthWarning(null, 100, 85))
        // 阈值 0 且上限已知：0/100 >= 0 → 告警（空队列也算，这是阈值 0 的语义）。
        assertTrue(isQueueDepthWarning(null, 100, 0))
    }

    @Test
    fun `full queue always warns`() {
        assertTrue(isQueueDepthWarning(100, 100, 100))
        assertTrue(isQueueDepthWarning(100, 100, 85))
    }

    @Test
    fun `over capacity warns`() {
        // MQ 允许临时超过 MAXDEPTH 后拒绝 put，读到的深度可能大于上限。
        assertTrue(isQueueDepthWarning(120, 100, 85))
    }

    @Test
    fun `tiny queue below threshold does not warn`() {
        // 1/10 = 10% < 85%：小队列不该因为绝对数小就沉默，也不该因为比例小而误报。
        assertFalse(isQueueDepthWarning(1, 10, 85))
        assertTrue(isQueueDepthWarning(9, 10, 85), "90% 应当告警")
    }
}
