package com.mqclient.uicommon.table

/**
 * 列排序方向。
 */
enum class SortDirection { ASCENDING, DESCENDING }

/**
 * 结果的排序状态：按第 [columnIndex] 列的 [direction] 排。
 */
data class SortState(val columnIndex: Int, val direction: SortDirection) {

    /**
     * 点击同一列表头后的下一状态：升 → 降 → null（无排序）。
     *
     * 第三态是**有意义**的：用户想回到「服务端返回的原始顺序」时，不该被迫再点一轮
     * 循环或重新执行查询。点其它列则重置为升序。
     */
    fun next(clicked: Int): SortState? = when {
        columnIndex != clicked -> SortState(clicked, SortDirection.ASCENDING)
        direction == SortDirection.ASCENDING -> SortState(clicked, SortDirection.DESCENDING)
        else -> null
    }
}

/**
 * 结果集的**混合类型比较器**：数据库一列里可能混着数字、字符串、时间、null。
 *
 * 规则按「谁更像数」优先：
 *  1. **null 永远排在最后**（不论升降序）—— 排在最前会把「没有值」误读成「最小值」；
 *  2. 两边都是数值 → 按数值比（`"10"` 与 `"9"` 若按字符串比会得出 10 < 9）；
 *  3. 只有一边是数值 → 数值在前（可计算的优先展示）；
 *  4. 其余按字符串比：先忽略大小写，再按原文兜底，保证全序稳定。
 *
 * 为什么不用 `Comparable` 强转：JDBC 返回的 `Any?` 类型不可控（BigDecimal /
 * Timestamp / byte[] / 自定义类型），`as Comparable<Any>` 会抛
 * `ClassCastException` —— 而排序是纯展示行为，不能因为某一列类型怪就把整个
 * 结果区打成错误态。
 */
object MixedTypeComparator : Comparator<Any?> {

    override fun compare(a: Any?, b: Any?): Int {
        if (a === b) return 0
        // null 恒排最后：这条与排序方向无关，由 sortRows 单独处理，避免降序时被翻到最前。
        if (a == null) return 1
        if (b == null) return -1

        val na = a.asSortableNumber()
        val nb = b.asSortableNumber()
        return when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> -1
            nb != null -> 1
            else -> {
                val sa = a.toString()
                val sb = b.toString()
                sa.compareTo(sb, ignoreCase = true).takeIf { it != 0 } ?: sa.compareTo(sb)
            }
        }
    }

    /**
     * 可参与数值比较的值。
     *
     * 字符串形态的数字也认：`SHOW` / 聚合类语句会把计数当字符串返回。
     * 但**不**用 `toDoubleOrNull` 的宽松语法（它接受 `1e5` / `0x1p3`），
     * 只认十进制写法 —— 否则 `"NaN"` / `"Infinity"` 会被当成数值排到两端。
     */
    private fun Any?.asSortableNumber(): Double? {
        if (this == null) return null
        if (this is Number) return toDouble().takeIf { it.isFinite() }
        val text = toString().trim()
        if (!isDecimalLiteral(text)) return null
        return text.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun isDecimalLiteral(text: String): Boolean {
        if (text.isEmpty()) return false
        // 只允许：可选符号 + 数字 + 至多一个小数点，至少一位数字。
        val body = text.removePrefix("-").removePrefix("+")
        if (body.isEmpty()) return false
        if (body.count { it == '.' } > 1) return false
        if (body.count { it.isDigit() } == 0) return false
        return body.all { it.isDigit() || it == '.' }
    }

    /**
     * 按 [state] 排序 [rows]；[state] 为 null 时原样返回（保持服务端顺序）。
     *
     * **null 恒在末尾**：降序时只翻转非 null 部分的比较结果，不让 null 跑到最前面。
     * 排序是稳定的（`sortedWith` 底层稳定），同值行保持原相对顺序 —— 翻页浏览时
     * 同值行的位置不会跳动。
     */
    fun sortRows(rows: List<List<Any?>>, state: SortState?): List<List<Any?>> {
        if (state == null) return rows
        val comparator = Comparator<List<Any?>> { x, y ->
            val a = x.getOrNull(state.columnIndex)
            val b = y.getOrNull(state.columnIndex)
            when {
                a == null && b == null -> 0
                a == null -> 1
                b == null -> -1
                else -> {
                    val c = compare(a, b)
                    if (state.direction == SortDirection.DESCENDING) -c else c
                }
            }
        }
        return rows.sortedWith(comparator)
    }
}
