package com.mqclient.olap.base.audit

import com.mqclient.olap.base.query.QueryAuditRecord

/**
 * 查询审计回调（**可注入**：app-core 落库查询历史时注入真实实现，模板层默认
 * [QueryAuditSink.Noop]）。
 *
 * 设计为单方法 fun interface：协议模块 / 宿主用 lambda 注入即可。回调在**终态**
 * （完成 / 失败 / 取消）时调用一次 —— 开始记录由终态里的 startedAt 覆盖，避免
 * 「开始写了、失败没写」的半条审计。
 *
 * 实现注意：回调运行在流收尾的协程上下文里（可挂起），实现方不应抛异常
 * （抛了会打断取流方 —— 审计失败不该影响查询结果；实现方自行兜异常）。
 */
fun interface QueryAuditSink {

    /** 一条查询到达终态（[QueryAuditRecord.status] != RUNNING）。 */
    suspend fun onQueryFinished(record: QueryAuditRecord)

    companion object {

        /** 默认空实现（什么都不做）—— 模板层无审计要求时使用。 */
        val Noop: QueryAuditSink = QueryAuditSink { }
    }
}
