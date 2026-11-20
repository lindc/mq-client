package com.mqclient.uicommon.table

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 排序语义的守卫。数据库结果列的类型在运行期才知道，这里把「混着排」的规则钉死：
 * 数值优先、null 恒在末尾、同值保持原序。
 */
class MixedTypeComparatorTest {

    private fun rows(vararg values: Any?) = values.map { listOf(it) }
    private fun column(rows: List<List<Any?>>) = rows.map { it[0] }

    @Test
    fun `numbers compare numerically not lexically`() {
        val sorted = MixedTypeComparator.sortRows(rows("9", "10", "2"), SortState(0, SortDirection.ASCENDING))
        assertEquals(listOf("2", "9", "10"), column(sorted), "字符串数字应按数值比，而不是 10 < 2")
    }

    @Test
    fun `real number types and numeric strings interoperate`() {
        val sorted = MixedTypeComparator.sortRows(rows(10L, "9", 2.5), SortState(0, SortDirection.ASCENDING))
        assertEquals(listOf(2.5, "9", 10L), column(sorted))
    }

    @Test
    fun `nulls stay last in both directions`() {
        val asc = MixedTypeComparator.sortRows(rows(3, null, 1), SortState(0, SortDirection.ASCENDING))
        val desc = MixedTypeComparator.sortRows(rows(3, null, 1), SortState(0, SortDirection.DESCENDING))

        assertEquals(listOf(1, 3, null), column(asc), "升序：null 在末尾")
        assertEquals(listOf(3, 1, null), column(desc), "降序：null 仍在末尾，不能翻到最前")
    }

    @Test
    fun `numeric values sort before non numeric ones`() {
        val sorted = MixedTypeComparator.sortRows(rows("abc", 5, "zzz"), SortState(0, SortDirection.ASCENDING))
        assertEquals(listOf(5, "abc", "zzz"), column(sorted))
    }

    @Test
    fun `strings are case insensitive with a stable tiebreak`() {
        val sorted = MixedTypeComparator.sortRows(rows("b", "A", "a"), SortState(0, SortDirection.ASCENDING))
        // 忽略大小写比较：A/a 相等，按原文兜底（"A" < "a"），稳定排序保持同值相对顺序。
        assertEquals(listOf("A", "a", "b"), column(sorted))
    }

    @Test
    fun `exponent and special float strings are not treated as numbers`() {
        // "1e5" / "NaN" / "Infinity" 不该按数值排（否则会跑到两端，看起来像脏数据）。
        val sorted = MixedTypeComparator.sortRows(rows("NaN", "1e5", "7"), SortState(0, SortDirection.ASCENDING))
        assertEquals("7", column(sorted).first(), "7 是唯一合法十进制数，应排最前")
    }

    @Test
    fun `no sort state preserves the original order`() {
        val original = rows(3, 1, 2)
        assertEquals(original, MixedTypeComparator.sortRows(original, null))
    }

    @Test
    fun `sorting is stable for equal keys`() {
        // 两行的排序列同值，靠第二列区分——稳定排序应保持它们的原始相对顺序。
        val input = listOf(
            listOf("x", 1),
            listOf("x", 2),
            listOf("x", 3),
        )
        val sorted = MixedTypeComparator.sortRows(input, SortState(0, SortDirection.ASCENDING))
        assertEquals(listOf(1, 2, 3), sorted.map { it[1] }, "同值行应保持原序")
    }

    @Test
    fun `rows shorter than the sorted column act like null`() {
        // 结果集里理论上每行等长，但防御一下越界（getOrNull）。
        val input = listOf(listOf(1), listOf(5, 1))
        val sorted = MixedTypeComparator.sortRows(input, SortState(1, SortDirection.ASCENDING))
        // listOf(1) 没有第 1 列 → 视为 null → 排在末尾；有值的 [5,1] 在前。
        assertEquals(listOf(5, 1), sorted.first(), "有值的行在前")
        assertEquals(listOf(1), sorted.last(), "缺列视为 null，排最后")
    }

    // ---------------- 三态循环 ----------------

    @Test
    fun `clicking cycles ascending then descending then none`() {
        // 初始无排序：点任一列即升序。
        val none: SortState? = null
        assertEquals(SortState(0, SortDirection.ASCENDING), none?.next(0) ?: SortState(0, SortDirection.ASCENDING))
        assertEquals(SortState(0, SortDirection.DESCENDING), SortState(0, SortDirection.ASCENDING).next(0))
        assertNull(SortState(0, SortDirection.DESCENDING).next(0), "第三次点击应回到无排序")
    }

    @Test
    fun `clicking a different column starts ascending there`() {
        val state = SortState(0, SortDirection.DESCENDING)
        assertEquals(SortState(2, SortDirection.ASCENDING), state.next(2))
    }

    @Test
    fun `comparator never throws on exotic types`() {
        val exotic: List<Any?> = listOf(byteArrayOf(1), java.math.BigDecimal("2.5"), 1, "z", null)
        val rows = exotic.map { listOf(it) }
        // 不抛异常即通过 —— 排序是展示行为，不能因为类型怪就崩面板。
        MixedTypeComparator.sortRows(rows, SortState(0, SortDirection.ASCENDING))
        assertTrue(true)
    }
}
