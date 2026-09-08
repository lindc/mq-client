package com.mqclient.core

import kotlinx.serialization.Serializable

/**
 * Live state of a connection, as shown by the sidebar's status dot and label.
 */
@Serializable
enum class ConnectionStatus(val label: String) {
    Connected("已连接"),
    Connecting("连接中..."),
    Failed("连接失败"),
    Disconnected("未连接"),
}

/**
 * One saved MQ connection. A connection always belongs to a middleware module
 * ([serviceId] is a [BusinessService.id]), but several connections can share one
 * module — the sidebar lists connections, not modules.
 */
@Serializable
data class ConnectionInfo(
    val id: String,
    /** User-facing name, e.g. "生产环境". */
    val name: String,
    val serviceId: String,
    /** Name of the MQ endpoint itself, e.g. "rabbit@mq-01". */
    val mqName: String,
    val host: String,
    val port: Int,
    val status: ConnectionStatus,
) {
    val endpoint: String get() = "$host:$port"
}

/**
 * In-memory connection list backed by the encrypted `~/.mqclient/connections.json`.
 *
 * The list is loaded once at class-init time via [ConfigStore.loadConnections];
 * if you need the UI to react to edits, refresh through
 * [reloadFromDisk] (currently nothing in the app mutates this list, so the
 * lazy lookup stays cheap).
 */
object ConnectionStore {

    /** Cached snapshot. Mutating this directly won't persist — go through [saveAll]. */
    var connections: List<ConnectionInfo> = ConfigStore.loadConnections()
        private set

    private val objectCounts: Map<String, Map<String, Int>> =
        mapOf(
            "mq-01" to mapOf("queue" to 12, "listener" to 3, "topic" to 0),
        )

    /** Number of objects of [objectType] behind [connectionId]; 0 when unknown. */
    fun countOf(connectionId: String, objectType: String): Int =
        objectCounts[connectionId]?.get(objectType) ?: 0

    /** Re-reads the encrypted connections file. No-op if [ConfigStore] errors out. */
    fun reloadFromDisk() {
        connections = ConfigStore.loadConnections()
    }

    /** Encrypts and writes [list] to disk; updates the cached snapshot. */
    fun saveAll(list: List<ConnectionInfo>) {
        ConfigStore.saveConnections(list)
        connections = list
    }
}