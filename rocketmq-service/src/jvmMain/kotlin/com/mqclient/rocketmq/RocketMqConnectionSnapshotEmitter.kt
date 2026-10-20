package com.mqclient.rocketmq

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStore
import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getString
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit

/**
 * RocketMQ 连接快照事件的组装与广播（`core.mq.connection.snapshot.created`）。
 *
 * 分工与 kafka 的 KafkaConnectionSnapshotEmitter 一致：连接的保存入口在 core
 * （`ConnectionStore.create/update`，落盘成功后广播 `core.connection.added/updated`）；
 * 但 options 里的键只有本模块认识 —— 所以由本模块订阅这两条事件、把 [ConnectionInfo]
 * 翻译成快照 payload 再广播，真正的写库在 app-core 的 `MqConnectionSnapshotHandler`。
 * 编辑连接时 core 会换发新 id（差异广播 = 旧 id deleted + 新 id added），本模块收到
 * 的 added 天然对应一条新快照，无需去重。
 */
internal object RocketMqConnectionSnapshotEmitter {

    /**
     * 从 [ConnectionInfo] 组装快照 payload：
     *  - `host` / `port` 用连接顶层字段（表单 doSave 时 host = NameServer 首地址、
     *    port = 9876，与展示口径一致）；
     *  - `target` = namesrvAddr（RocketMQ 的「逻辑容器」就是 NameServer 入口本身，
     *    多地址原样进快照）、`userName` = accessKey（未开 ACL 为空串）；
     *  - secretKey 等敏感字段不进 payload。
     */
    fun buildSnapshotPayload(connection: ConnectionInfo): Map<String, Any?> {
        val config = rocketMqConfigFromOptions(connection.options)
        return mapOf(
            "connectionId" to connection.id,
            "type" to MODULE_ID,
            "name" to connection.name,
            "host" to connection.host,
            "port" to connection.port,
            "target" to config.namesrvAddr,
            "userName" to config.accessKey,
        )
    }

    /**
     * `core.connection.added` 的订阅体（RocketMqModule 注册，调用前已确认是本模块
     * 的连接）。连接查不到（竞态：保存后被立刻删除）静默跳过。
     */
    fun onConnectionSaved(event: AppEvent, context: ServiceContext) {
        val connectionId = event.getString("connectionId") ?: return
        val connection = ConnectionStore.connections.value.firstOrNull { it.id == connectionId } ?: return
        context.emit(
            name = EventNames.MQ_CONNECTION_SNAPSHOT_CREATED,
            payload = buildSnapshotPayload(connection),
        )
    }
}
