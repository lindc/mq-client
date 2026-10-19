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

/**
 * 队列详情快照（GET /api/queues/{vhost}/{name} 详情端点的 UI 投影）：慢扫快照没有
 * arguments 与 queue_type，属性对话框实时拉取。arguments 已在 service 层渲染成
 * 纯文本（标量取原值，数组/对象 toString）—— UI 模块不依赖 kotlinx.serialization。
 */
data class RabbitMqQueueDetailSnapshot(
    val name: String,
    val state: String?,
    val queueType: String,
    val durable: Boolean,
    val autoDelete: Boolean,
    val exclusive: Boolean,
    val arguments: Map<String, String>,
    val messages: Long,
    val messagesReady: Long,
    val messagesUnacknowledged: Long,
    val consumers: Int,
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
    /** 删除绑定的路径参数（服务端生成）；实时查询才有，慢扫投影可空。 */
    val propertiesKey: String? = null,
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

/**
 * 一个集群节点的慢扫快照（`GET /api/nodes` 的投影）：概览页节点区块用。
 * 健康判定三层：`!running` → 异常；[memAlarm] / [diskFreeAlarm] 任一触发 → 告警
 * （RabbitMQ 会阻塞发布者）；其余 → 健康。
 */
data class RabbitMqNodeSnapshot(
    val name: String,
    /** disc（磁盘节点）/ ram（内存节点）。 */
    val type: String,
    val running: Boolean,
    /** 节点运行时长（毫秒），Tooltip 展示。 */
    val uptime: Long,
    /** 当前内存占用（字节），Tooltip 展示。 */
    val memUsed: Long,
    /** 内存高水位上限（字节），Tooltip 展示。 */
    val memLimit: Long,
    val memAlarm: Boolean,
    val diskFreeAlarm: Boolean,
)
