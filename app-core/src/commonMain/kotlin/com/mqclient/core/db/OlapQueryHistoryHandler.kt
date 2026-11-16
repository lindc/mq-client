package com.mqclient.core.db

import com.mqclient.core.CoreModule
import com.mqclient.core.concurrent.runCatchingCancellable
import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getLong
import com.mqclient.core.event.getString

/**
 * 处理 [EventNames.OLAP_QUERY_EXECUTED]：把 OLAP 查询的终态记录写进
 * olap_query_history 表（执行历史审计，只追加；**绝不写结果集**，见 olap_query.sq
 * 表头）。
 *
 * 分工与 [MqCommandHistoryHandler] 完全同构：事件的发出方是 OLAP 模块的查询门面
 * （StarRocksModule.executeQuery 的 onCompletion），payload 自包含，本类只做一件事
 * —— 忠实落库。任何失败都走错误通道上报，绝不上抛；写入失败不影响查询本身的
 * 结果。唯一的例外是**取消**：handler 被取消时原样上抛，否则取消会被当成
 * 「写入失败」报一条假错误。
 */
class OlapQueryHistoryHandler(private val repository: OlapQueryRepository) {

    suspend fun handle(event: AppEvent) {
        if (event.name != EventNames.OLAP_QUERY_EXECUTED) return

        // 必填字段缺一即放弃（事件由代码组装，缺字段属于编程错误，不值得落一条残缺记录）。
        val id = event.getString("recordId") ?: return
        val connectionId = event.getString("connectionId") ?: return
        val sqlText = event.getString("sqlText") ?: return
        val status = event.getString("status") ?: return
        val createdAt = event.getLong("createdAt") ?: return

        val record = OlapQueryRecord(
            id = id,
            connectionId = connectionId,
            sqlText = sqlText,
            status = status,
            durationMs = event.getLong("durationMs") ?: 0L,
            errorMessage = event.getString("errorMessage"),
            createdAt = createdAt,
        )

        runCatchingCancellable { repository.save(record) }
            .onFailure { error ->
                CoreModule.reportError(
                    message = "OLAP query history save failed (connectionId=$connectionId)",
                    source = "core.db",
                    error = error,
                )
            }
    }
}
