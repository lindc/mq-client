package com.mqclient.core.topology

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 跨模块的焦点传递：列表页点「在拓扑图中查看」时写入（按连接隔离），
 * [com.mqclient.topology.ui.TopologyPanel] 打开时消费；宿主（app-ui）订阅
 * [openRequests] 把对应连接切到拓扑 Tab。
 *
 * 之所以放 app-core：app-ui（宿主）、ibm-mq-ui（列表页）、topology-ui（面板）三者都依赖它，
 * 但 app-ui 不依赖 topology-ui，故不能放进 topology-ui。
 */
data class TopologyFocusRequest(
    val connectionId: String,
    val rootName: String,
    val rootType: String = "",
)

object TopologyFocusHolder {
    // 每个连接只保留最新一次焦点请求（按连接隔离），面板打开时消费掉。
    private val pending = mutableMapOf<String, TopologyFocusRequest>()

    // 额外广播给宿主，用于「切到拓扑 Tab」；extraBufferCapacity 保证新订阅者能补收最近一次。
    private val _openRequests = MutableSharedFlow<TopologyFocusRequest>(extraBufferCapacity = 16)
    val openRequests: Flow<TopologyFocusRequest> = _openRequests.asSharedFlow()

    /** 写入焦点（来自列表页卡片动作）并广播打开请求。 */
    fun set(connectionId: String, rootName: String, rootType: String = "") {
        val req = TopologyFocusRequest(connectionId, rootName, rootType)
        pending[connectionId] = req
        _openRequests.tryEmit(req)
    }

    /** 面板打开时取走本连接的最新焦点（取后清除，避免重复应用）。返回 null 表示无待消费焦点。 */
    fun consume(connectionId: String): TopologyFocusRequest? = pending.remove(connectionId)
}
