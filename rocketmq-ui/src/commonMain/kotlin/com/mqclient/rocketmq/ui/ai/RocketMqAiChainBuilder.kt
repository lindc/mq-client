package com.mqclient.rocketmq.ui.ai

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.rocketmq.RocketMqGroupLagSnapshot
import com.mqclient.rocketmq.RocketMqGroupSnapshot
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.RocketMqOffsetRowSnapshot
import com.mqclient.rocketmq.RocketMqTopicSnapshot
import com.mqclient.uicommon.AiAskRequest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 「询问 AI」链路上下文构建器（RocketMQ 版，与 ibm-mq / kafka / rabbitmq 同构）：
 * RocketMQ 的链路也是两段 ——
 *
 *   NAMESERVER（NameServer 地址，宿主节点）—hosts→ TOPIC（target，带队列数 / perm /
 *     逐 broker 路由）—consumed_by→ [订阅了该 topic 的消费组，带 lag / 逐队列行]
 *   NAMESERVER —hosts→ CONSUMER_GROUP（target，带成员 / 订阅 / 逐队列积压行）
 *
 * 数据口径与 kafka / rabbitmq 相同：**只读内存缓存，不碰集群** —— 目标不在缓存里
 * （缓存未就绪 / 对象刚删）时返回 null，调用方静默忽略，不阻塞提问。消费组的
 * consumeMode / subscribedTopics 来自对象慢扫（10min 级），逐队列 lag 行来自心跳
 * 快扫（15s 级，落缓存时已按 lag 降序截头）；拿不到的字段整体省略（组从未消费 /
 * CODE 17 / 离线是预期内现象），不阻塞提问。
 *
 * 链路线性化（三家同一约定）：多个消费组是兄弟节点，不建分支结构——依序排在
 * TOPIC 之后，TOPIC 的 relationToNext=consumed_by 指向它们，组节点之间无
 * relationToNext。挑选规则见 [selectConsumerGroups]。
 *
 * 产物是 chain **数组**的 JSON（不含 currentTime / userQuestion —— 由 ai-ui 的
 * AiPanelState 包装成完整上下文，见 AiAskRequest 的文档）。
 */
object RocketMqAiChainBuilder {

    /** relationToNext 取值（上下文 JSON 契约，见 ai-service 的 chat_history.sq 对 context_json 列的注释）。 */
    private const val REL_HOSTS = "hosts"
    private const val REL_CONSUMED_BY = "consumed_by"

    /** 节点类型（模块自定义词汇，与 objectType 同源）。 */
    private const val TYPE_NAMESERVER = "NAMESERVER"
    private const val TYPE_TOPIC = "TOPIC"
    private const val TYPE_GROUP = "CONSUMER_GROUP"

    /** 下游消费组上限：按总 lag 降序取前 N —— lag 大的组才是分析价值所在。 */
    internal const val MAX_CONSUMER_GROUPS = 4

    /** Topic 链里单组的逐队列 lag 行上限：本 topic 的行，防大扇出把上下文撑爆。 */
    internal const val MAX_TOPIC_QUEUE_LAG_ROWS = 8

    /** Group 链（target 自己）的逐队列积压行上限：积压分析只关心 lag 最大的前 N 个队列。 */
    internal const val MAX_GROUP_PROGRESS_ROWS = 10

    /**
     * 从已缓存的 topic 快照构建「询问 AI」请求。
     *
     * @return topic 在缓存里且连接配置可用时返回请求；否则返回 null，调用方忽略即可
     *         （与 kafka / rabbitmq 的「缓存未就绪静默忽略」同一语义）。
     */
    fun buildForTopic(connectionId: String, topicName: String): AiAskRequest? {
        val ctx = RocketMqModule.connectionOrNull(connectionId) ?: return null
        val namesrv = ctx.config.namesrvAddr.trim()
        if (namesrv.isBlank()) return null
        val service = RocketMqModule.serviceOrNull() ?: return null
        val topic = service.cachedTopics(connectionId).firstOrNull { it.name == topicName } ?: return null
        val groups = service.cachedGroups(connectionId)
        val lags = service.cachedGroupLags(connectionId)
        val consumers = selectConsumerGroups(topicName, groups, lags)
        val clusterName = service.cachedClusterInfo(connectionId)?.clusterName
        return AiAskRequest(
            connectionId = connectionId,
            objectType = TYPE_TOPIC,
            objectName = topic.name,
            targetLabel = topic.name,
            contextJson = topicChainJson(namesrv, clusterName, topic, consumers, lags),
        )
    }

    /**
     * 从已缓存的消费组快照构建「询问 AI」请求：组不在慢扫缓存里返回 null（静默忽略）。
     */
    fun buildForGroup(connectionId: String, groupName: String): AiAskRequest? {
        val ctx = RocketMqModule.connectionOrNull(connectionId) ?: return null
        val namesrv = ctx.config.namesrvAddr.trim()
        if (namesrv.isBlank()) return null
        val service = RocketMqModule.serviceOrNull() ?: return null
        val group = service.cachedGroups(connectionId).firstOrNull { it.name == groupName } ?: return null
        val lag = service.cachedGroupLags(connectionId)[groupName]
        val clusterName = service.cachedClusterInfo(connectionId)?.clusterName
        return AiAskRequest(
            connectionId = connectionId,
            objectType = TYPE_GROUP,
            objectName = group.name,
            targetLabel = group.name,
            contextJson = groupChainJson(namesrv, clusterName, group, lag),
        )
    }

    /**
     * 下游消费组挑选（纯函数，离线可测）：订阅了该 topic 的组（慢扫缓存的
     * subscribedTopics；组从未消费 / 离线时订阅列表为空，落不进任何 topic 的下游
     * —— 与「无下游」语义一致），按总 lag 降序取前 [MAX_CONSUMER_GROUPS] 个；
     * lag 缓存缺该组时按 0 计（仍入选，排序垫底）。
     */
    internal fun selectConsumerGroups(
        topicName: String,
        groups: List<RocketMqGroupSnapshot>,
        lags: Map<String, RocketMqGroupLagSnapshot>,
    ): List<RocketMqGroupSnapshot> =
        groups.asSequence()
            .filter { topicName in it.subscribedTopics }
            .sortedByDescending { lags[it.name]?.totalLag ?: 0L }
            .take(MAX_CONSUMER_GROUPS)
            .toList()

    /** Topic 链的 chain 数组 JSON（纯函数，离线可测；节点结构见类 KDoc）。 */
    internal fun topicChainJson(
        namesrvAddr: String,
        clusterName: String?,
        topic: RocketMqTopicSnapshot,
        consumers: List<RocketMqGroupSnapshot>,
        lags: Map<String, RocketMqGroupLagSnapshot>,
    ): String = buildJsonArray {
        add(nameserverNode(namesrvAddr, clusterName))
        add(
            buildJsonObject {
                put("name", topic.name)
                put("type", TYPE_TOPIC)
                put("isTarget", true)
                if (consumers.isNotEmpty()) put("relationToNext", REL_CONSUMED_BY)
                put(
                    "attributes",
                    buildJsonObject {
                        put("readQueueNums", topic.readQueueNums)
                        put("writeQueueNums", topic.writeQueueNums)
                        put("perm", topic.perm)
                        // 逐 broker 路由明细（多 Master 时看队列分布是否倾斜）。
                        put("routes", buildJsonArray {
                            topic.routes.forEach { route ->
                                add(
                                    buildJsonObject {
                                        put("brokerName", route.brokerName)
                                        put("readQueues", route.readQueueNums)
                                        put("writeQueues", route.writeQueueNums)
                                        put("perm", route.perm)
                                    },
                                )
                            }
                        })
                    },
                )
            },
        )
        consumers.forEach { group ->
            val lag = lags[group.name]
            add(
                buildJsonObject {
                    put("name", group.name)
                    put("type", TYPE_GROUP)
                    put("isTarget", false)
                    put(
                        "attributes",
                        buildJsonObject {
                            put("members", group.memberCount ?: 0)
                            group.consumeMode?.let { put("consumeMode", it) }
                            put("totalLag", lag?.totalLag ?: 0L)
                            // 该 topic 的逐队列 lag（快扫缓存已按 lag 降序），过滤 + 截上限。
                            lag?.rows
                                ?.filter { it.topic == topic.name }
                                ?.take(MAX_TOPIC_QUEUE_LAG_ROWS)
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { rows ->
                                    put("queueLags", buildJsonArray { rows.forEach { add(rowJson(it)) } })
                                }
                        },
                    )
                },
            )
        }
    }.toString()

    /** Group 链的 chain 数组 JSON（纯函数，离线可测）。 */
    internal fun groupChainJson(
        namesrvAddr: String,
        clusterName: String?,
        group: RocketMqGroupSnapshot,
        lag: RocketMqGroupLagSnapshot?,
    ): String = buildJsonArray {
        add(nameserverNode(namesrvAddr, clusterName))
        add(
            buildJsonObject {
                put("name", group.name)
                put("type", TYPE_GROUP)
                put("isTarget", true)
                put(
                    "attributes",
                    buildJsonObject {
                        put("members", group.memberCount ?: 0)
                        group.consumeMode?.let { put("consumeMode", it) }
                        if (group.subscribedTopics.isNotEmpty()) {
                            put("subscribedTopics", buildJsonArray {
                                group.subscribedTopics.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                            })
                        }
                        lag?.let {
                            put("totalLag", it.totalLag)
                            put("queueCount", it.queueCount)
                            // 逐队列积压行（缓存已按 lag 降序截头），再截到上下文上限。
                            it.rows.take(MAX_GROUP_PROGRESS_ROWS)
                                .takeIf { rows -> rows.isNotEmpty() }
                                ?.let { rows -> put("consumerProgress", buildJsonArray { rows.forEach { row -> add(rowJson(row)) } }) }
                        }
                    },
                )
            },
        )
    }.toString()

    /**
     * 逐队列行 → JSON（Topic 链的 queueLags 与 Group 链的 consumerProgress 共用；
     * 字段名与 Kafka 的分区 lag 行同风格，便于模型跨模块迁移理解）。
     */
    private fun rowJson(row: RocketMqOffsetRowSnapshot) = buildJsonObject {
        put("topic", row.topic)
        put("broker", row.broker)
        put("queueId", row.queueId)
        put("maxOffset", row.maxOffset)
        put("consumerOffset", row.consumerOffset)
        put("lag", row.lag)
    }

    /** NAMESERVER 宿主节点（连接配置快照，非敏感；clusterName 查得到就带上）。 */
    private fun nameserverNode(namesrvAddr: String, clusterName: String?) = buildJsonObject {
        put("name", namesrvAddr)
        put("type", TYPE_NAMESERVER)
        put("isTarget", false)
        put("relationToNext", REL_HOSTS)
        put(
            "attributes",
            buildJsonObject {
                put("nameServers", namesrvAddr)
                clusterName?.takeIf(String::isNotBlank)?.let { put("clusterName", it) }
            },
        )
    }
}

/**
 * 「询问 AI」在 Topic / 消费组卡片上的按钮构造：**图标区的第一个位置**（AI 绝对最左，
 * 与 ibm-mq / kafka / rabbitmq 各面板的 actions 拼接顺序一致），tooltip 用
 * `rocketmq_action_ask_ai`。
 */
internal fun askAiCardAction(onAskAi: () -> Unit): CardAction = CardAction(
    id = "rocketmq.ask_ai",
    icon = Icons.Default.Android,
    tooltip = "rocketmq_action_ask_ai",
    onClick = onAskAi,
)
