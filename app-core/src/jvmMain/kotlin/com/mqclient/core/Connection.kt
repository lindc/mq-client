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

    private val _connections = MutableStateFlow(ConfigStore.loadConnections().getOrElse { emptyList() })

    /** 可观测快照；UI 用 `collectAsState()` 订阅。直接改它无效 —— 走 [saveAll]。 */
    val connections: StateFlow<List<ConnectionInfo>> = _connections.asStateFlow()

    private val objectCounts: Map<String, Map<String, Int>> =
        mapOf(
            "mq-01" to mapOf("queue" to 12, "listener" to 3, "topic" to 0),
        )

    /** Number of objects of [objectType] behind [connectionId]; 0 when unknown. */
    fun countOf(connectionId: String, objectType: String): Int =
        objectCounts[connectionId]?.get(objectType) ?: 0

    /**
     * 重新读盘。**读失败时保留当前列表**：把失败当成空列表会让用户的连接在界面上
     * 凭空消失（他可能以为被删了，接着重新添加一遍）。失败原因由 [ConfigStore] 上报。
     */
    fun reloadFromDisk() {
        ConfigStore.loadConnections().onSuccess { _connections.value = it }
    }

    /**
     * Persists [list] into config.json and — only if that write succeeded —
     * publishes the new snapshot.
     *
     * 快照必须等落盘成功再换：否则界面会显示一份磁盘上没有的连接列表，下次启动
     * 又变回去。失败通过 [Result] 交给调用方决定是提示还是忽略（设置对话框已经
     * 能消费这个结果）。
     */
    fun saveAll(list: List<ConnectionInfo>): Result<Unit> {
        val result = ConfigStore.saveConnections(list)
        if (result.isSuccess) _connections.value = list
        return result
    }
}
