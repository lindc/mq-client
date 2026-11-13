package com.mqclient.core.db

/**
 * 一条 OLAP SQL 执行记录（对应 `olap_query_history` 表）。
 *
 * 刻意**不含结果集**：只审计「执行了什么、成没成、花了多久、为什么失败」。
 * 结果集可能 10 万行，落盘会让本地库体积爆炸（见 olap_query.sq 表头注释）。
 */
data class OlapQueryRecord(
    val id: String,
    val connectionId: String,
    val sqlText: String,
    /** SUCCESS / FAILED（取消归入 FAILED，errorMessage 记 cancelled）。 */
    val status: String,
    val durationMs: Long,
    val errorMessage: String?,
    val createdAt: Long,
) {
    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILED = "FAILED"
    }
}
