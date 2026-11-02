package com.mqclient.core.db

import com.mqclient.core.CoreModule
import com.mqclient.core.concurrent.runCatchingCancellable
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
 * report 能让用户看见）——**取消除外**：模块关闭 / 应用退出时 handler 被取消是正常
 * 控制流，必须原样上抛，否则会被当成「写入失败」报假错误。
 *
 * 表是追加式的：快照 id = 事件里的连接 UUIDv7（编辑连接时 ConnectionStore 换发新
 * id，新增事件天然带新 id），一个 id 只对应一份记录，旧 id 的记录原样保留 —— 没有
 * 更新 / 删除路径。
 */
class MqConnectionSnapshotHandler(private val repository: MqConnectionSnapshotRepository) {

    suspend fun handle(event: AppEvent) {
        if (event.name != EventNames.MQ_CONNECTION_SNAPSHOT_CREATED) return

        val id = event.getString("connectionId") ?: return
        val type = event.getString("type") ?: return

        val snapshot = MqConnectionSnapshot(
            id = id,
            type = type,
            // name / host / port 发出方恒给值（见 buildSnapshotPayload），防御性兜底
            // 只为满足 NOT NULL 列，不构成独立语义。
            name = event.getString("name").orEmpty(),
            host = event.getString("host").orEmpty(),
            port = event.getInt("port") ?: 0,
            target = event.getString("target"),
            userName = event.getString("userName"),
            createdAt = repository.now(),
        )

        runCatchingCancellable { repository.save(snapshot) }
            .onFailure { error ->
                CoreModule.reportError(
                    message = "Connection snapshot save failed (connectionId=$id)",
                    source = "core.db",
                    error = error,
                )
            }
    }
}
