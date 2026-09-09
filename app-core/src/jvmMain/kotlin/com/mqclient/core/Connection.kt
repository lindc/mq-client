package com.mqclient.core

/**
 * In-memory connection list backed by the `connections` section of the
 * single `~/.mqclient/config.json` (see [ConfigManager]).
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

    /** Re-reads the connections section from disk. No-op if [ConfigStore] errors out. */
    fun reloadFromDisk() {
        connections = ConfigStore.loadConnections()
    }

    /** Persists [list] into config.json and updates the cached snapshot. */
    fun saveAll(list: List<ConnectionInfo>) {
        ConfigStore.saveConnections(list)
        connections = list
    }
}
