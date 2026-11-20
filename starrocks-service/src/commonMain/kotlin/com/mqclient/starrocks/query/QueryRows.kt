package com.mqclient.starrocks.query

/**
 * 一次查询的原始结果：列名 + 行。轻量结构，不持有 ResultSet。
 *
 * 放在 commonMain（而非 `StarRocksQuery.kt` 所在的 jvmMain）：解析层
 * （`catalog/`、`cluster/`、`system/`）都是纯函数，应该在 common 源集里被
 * commonTest 直接测；把结果模型留在 jvmMain 会迫使它们跟着下沉，或者逼测试
 * 去 jvmTest。
 */
data class QueryRows(
    val columns: List<String>,
    val rows: List<List<Any?>>,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
}
