package com.mqclient.rabbitmq

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 集群元信息（建连时 `GET /api/overview` 的投影）：验证通过后由
 * [RabbitMqConnectionManager] 写进连接缓存（key 见 RabbitMqScanService 的
 * KEY_CLUSTER_INFO），概览页顶部的集群名 / 版本从这里读。
 */
data class RabbitMqClusterInfoSnapshot(
    val clusterName: String,
    /** RabbitMQ broker 版本（如 4.3.6）。 */
    val rabbitmqVersion: String,
    /** 验证时命中的节点（如 rabbit@rabbit1）。 */
    val node: String,
)

/** 绑定 arguments 的展示文本：空 map → "-"；否则 k=v 逗号拼接（JsonPrimitive 取 content）。 */
internal fun formatBindingArguments(arguments: Map<String, JsonElement>): String =
    if (arguments.isEmpty()) {
        "-"
    } else {
        arguments.entries.joinToString(", ") { (key, value) ->
            val text = (value as? JsonPrimitive)?.content ?: value.toString()
            "$key=$text"
        }
    }

/** 一条队列的慢扫快照：静态属性（布局 / 持久化），计数另走快扫的 [RabbitMqQueueStatusSnapshot]。 */
data class RabbitMqQueueSnapshot(
    val name: String,
    /** running / idle / down…（多节点集群取首节点状态）。 */
    val state: String?,
    val durable: Boolean,
    val autoDelete: Boolean,
    val exclusive: Boolean,
)

/** 一条交换机的慢扫快照。name="" 是各 vhost 都有的默认交换机（UI 按「(AMQP default)」展示）。 */
data class RabbitMqExchangeSnapshot(
    val name: String,
    /** direct / fanout / topic / headers。 */
    val type: String,
    val durable: Boolean,
    val autoDelete: Boolean,
    /** internal 交换机不直接收发布，仅作为交换机间路由的中转。 */
    val internal: Boolean,
)

/** 一条绑定关系（交换机 → 队列 / 交换机）：拓扑图的关系来源。 */
data class RabbitMqBindingSnapshot(
    val source: String,
    val destination: String,
    /** queue / exchange。 */
    val destinationType: String,
    val routingKey: String,
    /** x-arguments（如备选交换机等）；空 = 无自定义参数。 */
    val arguments: Map<String, JsonElement> = emptyMap(),
) {
    /** arguments 的展示文本（属性对话框 Bindings 表用）。 */
    val argumentsText: String get() = formatBindingArguments(arguments)
}

/** 一条队列的状态快照（快扫产物，全量替换）：消息堆积与消费者的新鲜计数。 */
data class RabbitMqQueueStatusSnapshot(
    val name: String,
    val state: String?,
    val messages: Long,
    val messagesReady: Long,
    val messagesUnacknowledged: Long,
    val consumers: Int,
)
