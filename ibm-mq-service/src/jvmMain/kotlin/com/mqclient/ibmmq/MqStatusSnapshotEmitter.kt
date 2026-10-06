package com.mqclient.ibmmq

import com.mqclient.core.CoreModule
import com.mqclient.core.db.MqStatusRecord
import com.mqclient.core.event.EventNames
import com.mqclient.core.newUuidV7
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.pcf.channelStatusName

/**
 * 状态采集快照事件的组装与广播（`core.mq.status.snapshot.created`）。
 *
 * 调用点在扫描任务（IbmMQService.scanMessageCounts / scanObjects）——**一次扫描
 * 打包成一个事件**（一批几十条，避免逐条事件的开销），真正的写库在 app-core 的
 * MqStatusHistoryHandler（整批一个事务）。原有数据流向（缓存 write-through、
 * *.scanned 事件）不动，这里是纯增量的旁路。
 *
 * 数值 / 文本规则：数值类属性（深度）填 numericValue（propertyValue 存字符串形式）；
 * 状态类属性（RUNNING / STOPPED …）只填 propertyValue。
 */
internal object MqStatusSnapshotEmitter {

    /**
     * 消息心跳扫描：队列深度（数值）+ 通道运行状态（文本）。[queueDepths] /
     * [channelStatuses] 是 scanner 的原始产物（名称 → 深度 / MQIACH_CHANNEL_STATUS 码）。
     */
    fun emitHeartbeat(
        connectionId: String,
        queueDepths: List<Pair<String, Int>>,
        channelStatuses: List<Pair<String, Int>>,
    ) {
        val now = System.currentTimeMillis()
        val records = buildList {
            queueDepths.forEach { (name, depth) ->
                add(numericRecord(connectionId, "QUEUE", name, "depth", depth.toDouble(), now))
            }
            channelStatuses.forEach { (name, statusCode) ->
                val statusName = channelStatusName(statusCode) ?: statusCode.toString()
                add(textRecord(connectionId, "CHANNEL", name, "status", statusName, now))
            }
        }
        emit(connectionId, records)
    }

    /**
     * 对象慢扫：监听器运行状态（文本）。队列深度 / 通道状态已由 10 秒级心跳覆盖，
     * 慢扫不重复记（同一采样点两份只是翻倍行数）；监听器没有秒级任务，只有这里有。
     */
    fun emitListenerStatuses(connectionId: String, listeners: List<MqObjectSnapshot>) {
        val now = System.currentTimeMillis()
        val records = listeners.mapNotNull { listener ->
            listener.listenerStatus?.let { status ->
                textRecord(connectionId, "LISTENER", listener.name, "status", status, now)
            }
        }
        emit(connectionId, records)
    }

    private fun numericRecord(
        connectionId: String,
        objectType: String,
        objectName: String,
        propertyName: String,
        value: Double,
        now: Long,
    ): MqStatusRecord = MqStatusRecord(
        id = newUuidV7(),
        connectionId = connectionId,
        objectType = objectType,
        objectName = objectName,
        propertyName = propertyName,
        propertyValue = value.toString(),
        numericValue = value,
        createTime = now,
    )

    private fun textRecord(
        connectionId: String,
        objectType: String,
        objectName: String,
        propertyName: String,
        value: String,
        now: Long,
    ): MqStatusRecord = MqStatusRecord(
        id = newUuidV7(),
        connectionId = connectionId,
        objectType = objectType,
        objectName = objectName,
        propertyName = propertyName,
        propertyValue = value,
        numericValue = null,
        createTime = now,
    )

    /** 空批次不发（扫描结果为空也是事实，但没有可落的数据）。发射失败静默 —— 采集旁路不影响扫描主流程。 */
    private fun emit(connectionId: String, records: List<MqStatusRecord>) {
        if (records.isEmpty()) return
        val bus = CoreModule.eventBusOrNull ?: return
        runCatching {
            bus.emit(
                name = EventNames.MQ_STATUS_SNAPSHOT_CREATED,
                payload = mapOf("records" to records),
                source = MODULE_ID,
            )
        }
    }
}
