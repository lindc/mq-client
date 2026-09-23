package com.mqclient.core.db

import com.mqclient.core.CoreModule
import com.mqclient.core.event.AppEvent
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getString
import com.mqclient.core.event.getInt

/**
 * 处理 [EventNames.MQ_CONNECTION_SNAPSHOT_CREATED]：把连接快照写进 mq_connection 表。
 *
 * 分工：事件的**发出方**是各 MQ 模块（只有它知道自己 options 里哪个键是逻辑容器 /
 * 用户名，见 ibm-mq 模块的 buildSnapshotPayload），本类只做一件事 —— 忠实落库。
 * 任何失败都走错误通道上报，绝不上抛（EventBus 会就地吃掉 handler 异常，但显式
 * report 能让用户看见）。
 *
 * 同一 connection_id 每次事件都插新行（表就是历史链），不做去重 —— 「保存了但什么
 * 都没改」在发出方已由 ConnectionStore 的差异广播挡掉（内容没变不发 updated）。
 */
class MqConnectionSnapshotHandler(private val repository: MqConnectionSnapshotRepository) {

    suspend fun handle(event: AppEvent) {
        if (event.name != EventNames.MQ_CONNECTION_SNAPSHOT_CREATED) return

        val connectionId = event.getString("connectionId") ?: return
        val type = event.getString("type") ?: return

        val snapshot = MqConnectionSnapshot(
            id = 0,
            connectionId = connectionId,
            type = type,
            name = event.getString("name"),
            host = event.getString("host"),
            port = event.getInt("port"),
            target = event.getString("target"),
            userName = event.getString("userName"),
            createdTime = repository.now(),
        )

        runCatching { repository.save(snapshot) }
            .onFailure { error ->
                CoreModule.reportError(
                    message = "连接快照写入失败（connectionId=$connectionId）",
                    source = "core.db",
                    error = error,
                )
            }
    }
}
