package com.mqclient.core

/**
 * Live state of a connection, as shown by the sidebar's status dot and label.
 */
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
 * In-memory connection list, standing in until real connection management
 * (create / edit / persist) lands. Counts are placeholder too: only a connected
 * endpoint can report how many objects of a type it holds.
 */
object ConnectionStore {
    val connections: List<ConnectionInfo> =
        listOf(
            ConnectionInfo(
                id = "mq-01",
                name = "生产环境",
                serviceId = "rabbitmq",
                mqName = "rabbit@mq-01",
                host = "192.168.1.100",
                port = 5672,
                status = ConnectionStatus.Connected,
            ),
            ConnectionInfo(
                id = "mq-02",
                name = "预发布环境",
                serviceId = "rabbitmq",
                mqName = "rabbit@mq-02",
                host = "192.168.1.102",
                port = 5672,
                status = ConnectionStatus.Failed,
            ),
            ConnectionInfo(
                id = "mq-03",
                name = "测试环境",
                serviceId = "kafka",
                mqName = "kafka@mq-03",
                host = "192.168.1.103",
                port = 5672,
                status = ConnectionStatus.Connecting,
            ),
            ConnectionInfo(
                id = "mq-04",
                name = "本地开发",
                serviceId = "ibm-mq",
                mqName = "qm@mq-04",
                host = "192.168.1.104",
                port = 5672,
                status = ConnectionStatus.Disconnected,
            ),
        )

    private val objectCounts: Map<String, Map<String, Int>> =
        mapOf(
            "mq-01" to mapOf("queue" to 12, "listener" to 3, "topic" to 0),
        )

    /** Number of objects of [objectType] behind [connectionId]; 0 when unknown. */
    fun countOf(connectionId: String, objectType: String): Int =
        objectCounts[connectionId]?.get(objectType) ?: 0
}
