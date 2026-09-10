package com.mqclient.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory connection list backed by the `connections` section of the
 * single `~/.mqclient/config.json` (see [ConfigManager]).
 *
 * The list is loaded once at init time via [ConfigStore.loadConnections] and then
 * kept as **observable state**: [connections] is a [StateFlow], so the UI
 * (`collectAsState()`) re-renders on [saveAll] / [reloadFromDisk] without anyone
 * having to remember to refresh. Core deliberately exposes coroutines' StateFlow
 * instead of Compose's State — app-core stays free of the UI framework.
 *
 * 与事件总线的分工：`core.connection.added/deleted` 是「发生了什么」（事件），
 * 这里是「现在有哪些」（状态），订阅方收到事件后再决定要不要落盘。
 */
object ConnectionStore {

    private val _connections = MutableStateFlow(ConfigStore.loadConnections())

    /** 可观测快照；UI 用 `collectAsState()` 订阅。直接改它无效 —— 走 [saveAll]。 */
    val connections: StateFlow<List<ConnectionInfo>> = _connections.asStateFlow()

    private val objectCounts: Map<String, Map<String, Int>> =
        mapOf(
            "mq-01" to mapOf("queue" to 12, "listener" to 3, "topic" to 0),
        )

    /** Number of objects of [objectType] behind [connectionId]; 0 when unknown. */
    fun countOf(connectionId: String, objectType: String): Int =
        objectCounts[connectionId]?.get(objectType) ?: 0

    /** Re-reads the connections section from disk. No-op if [ConfigStore] errors out. */
    fun reloadFromDisk() {
        _connections.value = ConfigStore.loadConnections()
    }

    /** Persists [list] into config.json and publishes the new snapshot. */
    fun saveAll(list: List<ConnectionInfo>) {
        ConfigStore.saveConnections(list)
        _connections.value = list
    }
}
