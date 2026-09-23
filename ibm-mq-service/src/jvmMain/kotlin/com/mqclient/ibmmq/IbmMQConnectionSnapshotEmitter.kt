package com.mqclient.ibmmq

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStore
import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getString
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit

/**
 * IBM MQ 连接快照事件的组装与广播（`core.mq.connection.snapshot.created`）。
 *
 * 分工：连接的保存入口在 core（`ConnectionStore.create/update`，落盘成功后广播
 * `core.connection.added/updated`）；但 options 里的键只有本模块认识 —— 所以由本
 * 模块订阅这两条事件、把 [ConnectionInfo] 翻译成快照 payload 再广播，真正的写库
 * 在 app-core 的 `MqConnectionSnapshotHandler`。编辑连接时 core 会换发新 id
 * （差异广播 = 旧 id deleted + 新 id added），本模块收到的 added 天然对应一条
 * 新快照，connection_id 与快照行一一对应，无需去重。
 */
internal object IbmMQConnectionSnapshotEmitter {

    /**
     * 从 [ConnectionInfo] 组装快照 payload：
     *  - `host` / `port` 用连接顶层字段（单主机）；
     *  - `target` = qmgr_name、`userName` = username，从 options 解析
     *    （解析失败回落默认值，见 [ibmMQConfigFromOptions] —— 快照忠实记录回落的值）；
     *  - 密码等敏感字段不进 payload。
     */
    fun buildSnapshotPayload(connection: ConnectionInfo): Map<String, Any?> {
        val config = ibmMQConfigFromOptions(connection.options)
        return mapOf(
            "connectionId" to connection.id,
            "type" to MODULE_ID,
            "name" to connection.name,
            "host" to connection.host,
            "port" to connection.port,
            "target" to config.queueManager,
            "userName" to config.username,
        )
    }

    /**
     * `core.connection.added` / `core.connection.updated` 的订阅体（IbmMQModule 注册，
     * 调用前已确认是本模块的连接）。按事件里的 connectionId 查当前连接快照组装 payload；
     * 连接查不到（竞态：保存后被立刻删除）静默跳过 —— 快照晚一拍没有语义损失。
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
