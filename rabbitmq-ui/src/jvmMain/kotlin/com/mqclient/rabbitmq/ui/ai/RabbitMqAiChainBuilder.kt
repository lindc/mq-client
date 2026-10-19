package com.mqclient.rabbitmq.ui.ai

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.rabbitmq.RabbitMqBindingSnapshot
import com.mqclient.rabbitmq.RabbitMqExchangeSnapshot
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.RabbitMqQueueSnapshot
import com.mqclient.rabbitmq.RabbitMqQueueStatusSnapshot
import com.mqclient.uicommon.AiAskRequest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 「询问 AI」链路上下文构建器（RabbitMQ 版，与 ibm-mq / kafka 同构）：数据模型里
 * 没有多跳链路，路由就是一段 ——
 *
 *   VHOST —hosts→ [上游交换机…] —routes_to→ QUEUE（target）
 *   VHOST —hosts→ EXCHANGE（target）—routes_to→ [下游队列…]
 *
 * 数据口径与 kafka 相同：**只读慢扫 / 快扫缓存，不碰集群** —— 目标不在缓存里
 * （缓存未就绪 / 对象刚删）时返回 null，调用方静默忽略，不阻塞提问。
 *
 * 线性化约定与 MQ / Kafka 一致：兄弟节点依序排、不建分支；多条绑定去重后合并
 * 同一交换机的 routingKeys。默认交换机（name=""）的隐式绑定不入链（每个队列
 * 恒有一条，无分析价值）；默认交换机作为 target 时节点名编址 amq.default
 * （管理 API 同款口径）。
 *
 * 产物是 chain **数组**的 JSON（不含 currentTime / userQuestion —— 由 ai-ui 的
 * AiPanelState 包装成完整上下文，见 AiAskRequest 的文档）。
 */
object RabbitMqAiChainBuilder {

    /** relationToNext 取值（上下文 JSON 契约，见 ai-service 的 chat_history.sq 对 context_json 列的注释）。 */
    private const val REL_HOSTS = "hosts"
    private const val REL_ROUTES_TO = "routes_to"

    /** 下游队列上限：按消息数降序取前 N —— 堆积大的队列才是分析价值所在。 */
    internal const val MAX_DOWNSTREAM_QUEUES = 4

    /** 单个上游交换机的 routingKey 行上限：防扇入大的交换机把上下文撑爆。 */
    internal const val MAX_ROUTING_KEYS = 8

    /** 默认交换机在管理 API 里的编址（name="" 的可读名）。 */
    private const val DEFAULT_EXCHANGE = "amq.default"

    /** 一条上游交换机（绑定去重合并后）：交换机名 + 各绑定的 routingKey（空 key 记空串）。 */
    data class UpstreamExchange(val name: String, val routingKeys: List<String>)

    /** 一条下游队列：队列名 + 快扫计数（缓存缺该队列时为 null，attributes 相应省略）。 */
    data class DownstreamQueue(val name: String, val status: RabbitMqQueueStatusSnapshot?)

    /** 队列为 target：上游交换机（routes_to 进来）→ 队列。 */
    fun buildForQueue(connectionId: String, queueName: String): AiAskRequest? {
        val service = RabbitMqModule.serviceOrNull() ?: return null
        val queue = service.cachedQueues(connectionId).firstOrNull { it.name == queueName } ?: return null
        val vhost = RabbitMqModule.connectionOrNull(connectionId)?.config?.vhost ?: return null
        val status = service.cachedQueueStatus(connectionId)[queueName]
        val upstream = selectUpstreamExchanges(queueName, service.cachedBindings(connectionId))
        val exchangeTypes = service.cachedExchanges(connectionId).associate { it.name to it.type }
        return AiAskRequest(
            connectionId = connectionId,
            objectType = "QUEUE",
            objectName = queueName,
            targetLabel = queueName,
            contextJson = queueChainJson(vhost, upstream, exchangeTypes, queue, status),
        )
    }

    /** 交换机为 target：交换机 → routes_to 出去的队列。 */
    fun buildForExchange(connectionId: String, exchangeName: String): AiAskRequest? {
        val service = RabbitMqModule.serviceOrNull() ?: return null
        val exchange = service.cachedExchanges(connectionId).firstOrNull { it.name == exchangeName } ?: return null
        val vhost = RabbitMqModule.connectionOrNull(connectionId)?.config?.vhost ?: return null
        val downstream = selectDownstreamQueues(
            exchangeName,
            service.cachedBindings(connectionId),
            service.cachedQueueStatus(connectionId),
        )
        return AiAskRequest(
            connectionId = connectionId,
            objectType = "EXCHANGE",
            objectName = exchangeName,
            targetLabel = exchangeName.ifEmpty { DEFAULT_EXCHANGE },
            contextJson = exchangeChainJson(vhost, exchange, downstream),
        )
    }

    /**
     * 上游交换机挑选（纯函数，离线可测）：destination=该队列 且 destinationType=queue
     * 的绑定，按 source 去重合并 routingKeys（首见顺序）；默认交换机（source=""）
     * 的隐式绑定不入链。
     */
    internal fun selectUpstreamExchanges(
        queueName: String,
        bindings: List<RabbitMqBindingSnapshot>,
    ): List<UpstreamExchange> =
        bindings.asSequence()
            .filter { it.destination == queueName && it.destinationType == "queue" && it.source.isNotEmpty() }
            .fold(mutableMapOf<String, MutableList<String>>()) { acc, b ->
                acc.getOrPut(b.source) { mutableListOf() }.add(b.routingKey)
                acc
            }
            .map { (name, keys) -> UpstreamExchange(name, keys.take(MAX_ROUTING_KEYS)) }
            .toList()

    /**
     * 下游队列挑选（纯函数，离线可测）：source=该交换机 且 destinationType=queue 的
     * 绑定，按 destination 去重，快扫计数按消息数降序取前 [MAX_DOWNSTREAM_QUEUES]
     * 个（计数缺该队列时按 0 计，仍入选，排序垫底）。
     */
    internal fun selectDownstreamQueues(
        exchangeName: String,
        bindings: List<RabbitMqBindingSnapshot>,
        status: Map<String, RabbitMqQueueStatusSnapshot>,
    ): List<DownstreamQueue> =
        bindings.asSequence()
            .filter { it.source == exchangeName && it.destinationType == "queue" }
            .map { it.destination }
            .distinct()
            .map { DownstreamQueue(it, status[it]) }
            .sortedByDescending { it.status?.messages ?: 0L }
            .take(MAX_DOWNSTREAM_QUEUES)
            .toList()

    /** VHOST 宿主节点（三家 chain 的第一节点同位）。 */
    private fun vhostNode(vhost: String) = buildJsonObject {
        put("name", vhost)
        put("type", "VHOST")
        put("isTarget", false)
        put("relationToNext", REL_HOSTS)
        put("attributes", buildJsonObject { put("vhost", vhost) })
    }

    /** 队列 chain JSON（纯函数，离线可测；节点结构见类 KDoc）。 */
    internal fun queueChainJson(
        vhost: String,
        upstream: List<UpstreamExchange>,
        exchangeTypes: Map<String, String>,
        queue: RabbitMqQueueSnapshot,
        status: RabbitMqQueueStatusSnapshot?,
    ): String = buildJsonArray {
        add(vhostNode(vhost))
        upstream.forEach { up ->
            add(
                buildJsonObject {
                    put("name", up.name)
                    put("type", "EXCHANGE")
                    put("isTarget", false)
                    put("relationToNext", REL_ROUTES_TO)
                    put(
                        "attributes",
                        buildJsonObject {
                            exchangeTypes[up.name]?.let { put("type", it) }
                            put("routingKeys", buildJsonArray { up.routingKeys.forEach { add(JsonPrimitive(it)) } })
                        },
                    )
                },
            )
        }
        add(
            buildJsonObject {
                put("name", queue.name)
                put("type", "QUEUE")
                put("isTarget", true)
                put(
                    "attributes",
                    buildJsonObject {
                        (status?.state ?: queue.state)?.let { put("state", it) }
                        put("durable", queue.durable)
                        put("autoDelete", queue.autoDelete)
                        put("exclusive", queue.exclusive)
                        // 计数来自快扫缓存，未扫到（null）就省略 —— 不给 AI 假 0。
                        status?.let {
                            put("messages", it.messages)
                            put("messagesReady", it.messagesReady)
                            put("messagesUnacknowledged", it.messagesUnacknowledged)
                            put("consumers", it.consumers)
                        }
                    },
                )
            },
        )
    }.toString()

    /** 交换机 chain JSON（纯函数，离线可测）。 */
    internal fun exchangeChainJson(
        vhost: String,
        exchange: RabbitMqExchangeSnapshot,
        downstream: List<DownstreamQueue>,
    ): String = buildJsonArray {
        add(vhostNode(vhost))
        add(
            buildJsonObject {
                put("name", exchange.name.ifEmpty { DEFAULT_EXCHANGE })
                put("type", "EXCHANGE")
                put("isTarget", true)
                if (downstream.isNotEmpty()) put("relationToNext", REL_ROUTES_TO)
                put(
                    "attributes",
                    buildJsonObject {
                        put("type", exchange.type)
                        put("durable", exchange.durable)
                        put("autoDelete", exchange.autoDelete)
                        put("internal", exchange.internal)
                    },
                )
            },
        )
        downstream.forEach { q ->
            add(
                buildJsonObject {
                    put("name", q.name)
                    put("type", "QUEUE")
                    put("isTarget", false)
                    put(
                        "attributes",
                        buildJsonObject {
                            q.status?.state?.let { put("state", it) }
                            q.status?.let {
                                put("messages", it.messages)
                                put("consumers", it.consumers)
                            }
                        },
                    )
                },
            )
        }
    }.toString()
}

/**
 * 「询问 AI」在队列 / 交换机卡片上的按钮构造：**图标区的第一个位置**（AI 绝对最左，
 * 与 ibm-mq / kafka 各面板的 actions 拼接顺序一致），tooltip 用 `rabbitmq_ui_action_ask_ai`。
 */
internal fun askAiCardAction(onAskAi: () -> Unit): CardAction = CardAction(
    id = "rabbitmq.ask_ai",
    icon = Icons.Default.Android,
    tooltip = "rabbitmq_ui_action_ask_ai",
    onClick = onAskAi,
)
