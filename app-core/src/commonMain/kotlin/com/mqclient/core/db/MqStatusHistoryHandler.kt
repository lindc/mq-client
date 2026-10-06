package com.mqclient.core.db

import com.mqclient.core.CoreModule
import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.EventNames

/**
 * 处理 [EventNames.MQ_STATUS_SNAPSHOT_CREATED]：把一批状态采集记录写进 mq_status 表。
 *
 * 分工：事件的发出方是各 MQ 模块的扫描任务（IBM MQ = scanMessageCounts /
 * scanObjects 经 MqStatusSnapshotEmitter，一次扫描一个事件、payload 自包含），
 * 本类只做一件事 —— 整批一个事务落库。任何失败都走错误通道上报，绝不上抛
 * （EventBus 会就地吃掉 handler 异常，但显式 report 能让用户看见）；写入失败
 * 不影响扫描任务本身。
 */
class MqStatusHistoryHandler(private val repository: MqStatusHistoryRepository) {

    suspend fun handle(event: AppEvent) {
        if (event.name != EventNames.MQ_STATUS_SNAPSHOT_CREATED) return

        // payload 里是类型化的 List<MqStatusRecord>（进程内事件不序列化）；按元素
        // 逐个过滤而不是整体强转——类型不符的脏元素静默丢弃，不拖垮整批。
        val records = (event.payload["records"] as? List<*>)
            ?.filterIsInstance<MqStatusRecord>()
            .orEmpty()
        if (records.isEmpty()) return

        runCatching { repository.saveBatch(records) }
            .onFailure { error ->
                CoreModule.reportError(
                    message = "状态采集写入失败（${records.size} 条，connectionId=${records.first().connectionId}）",
                    source = "core.db",
                    error = error,
                )
            }
    }
}
