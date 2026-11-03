package com.mqclient.kafka.ui.ai

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import com.mqclient.kafka.KafkaGroupLagSnapshot
import com.mqclient.kafka.KafkaGroupSnapshot
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.ui.KafkaTopicInfo
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.uicommon.AiAskRequest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 「询问 AI」链路上下文构建器（Kafka 版，与 ibm-mq 的 AiChainBuilder 同构但简单得多）：
 * Kafka 的数据模型里没有 MQ 那种「别名 / 传输队列 / 通道」的多跳链路，主题的上下游
 * 是两段：
 *
 *   CLUSTER（bootstrap servers，宿主节点）—hosts→ TOPIC（目标，带分区 / 副本属性）
 *     —consumed_by→ CONSUMER_GROUP（订阅了该 topic 的组，带状态 / 总 lag / 分区级 lag）
 *
 * 数据口径与 ibm-mq 相同：**只读内存缓存，不碰集群** —— topic 入参来自 TopicListPanel
 * 已加载的列表（进入 Tab 时的实时查询产物）；消费组来自对象慢扫缓存（cachedGroups，
 * 10min），lag 来自快扫心跳缓存（cachedGroupLags，30s）。缓存未就绪 / 无消费组时链路
 * 退回 CLUSTER→TOPIC 两节点（与旧行为一致），不阻塞提问。
 *
 * 链路线性化（MQ AiChainBuilder 同一约定）：多个消费组是兄弟节点，不建分支结构——
 * 依序排在 TOPIC 之后，TOPIC 的 relationToNext=consumed_by 指向它们，组节点之间无
 * relationToNext。挑选规则见 [selectConsumerGroups]。
 *
 * 产物是 chain **数组**的 JSON（不含 currentTime / userQuestion —— 由 ai-ui 的
 * AiPanelState 包装成完整上下文，见 AiAskRequest 的文档）。
 */
object KafkaAiChainBuilder {

    /** relationToNext 取值（上下文 JSON 契约，见 ai-service 的 chat_history.sq 对 context_json 列的注释）。 */
    private const val REL_HOSTS = "hosts"
    private const val REL_CONSUMED_BY = "consumed_by"

    /** 下游消费组上限：按总 lag 降序取前 N —— lag 大的组才是分析价值所在。 */
    internal const val MAX_CONSUMER_GROUPS = 4

    /** 单组的分区 lag 行上限：防大 topic 把上下文撑爆（超出只留前 N 个分区）。 */
    internal const val MAX_LAG_ROWS = 8

    /**
     * 从已加载的 topic 信息构建「询问 AI」请求。
     *
     * @return 连接上下文存在且 bootstrap servers 非空时返回请求；否则返回 null，
     *         调用方忽略即可（与 ibm-mq 的「缓存未就绪静默忽略」同一语义）。
     */
    fun build(connectionId: String, topic: KafkaTopicInfo): AiAskRequest? {
        val ctx = KafkaModule.connectionOrNull(connectionId) ?: return null
        val bootstrap = ctx.config.bootstrapServers.trim()
        if (bootstrap.isBlank()) return null
        val service = KafkaModule.serviceOrNull()
        val lags = service?.cachedGroupLags(connectionId).orEmpty()
        val consumers = selectConsumerGroups(
            topicName = topic.name,
            groups = service?.cachedGroups(connectionId).orEmpty(),
            lags = lags,
        )
        return AiAskRequest(
            connectionId = connectionId,
            objectType = "TOPIC",
            objectName = topic.name,
            targetLabel = topic.name,
            contextJson = chainJson(bootstrap, topic, consumers, lags),
        )
    }

    /**
     * 下游消费组挑选（纯函数，离线可测）：订阅了该 topic 的组，按总 lag 降序取前
     * [MAX_CONSUMER_GROUPS] 个；lag 缓存缺该组时按 0 计（仍入选，排序垫底）。
     */
    internal fun selectConsumerGroups(
        topicName: String,
        groups: List<KafkaGroupSnapshot>,
        lags: Map<String, KafkaGroupLagSnapshot>,
    ): List<KafkaGroupSnapshot> =
        groups.asSequence()
            .filter { topicName in it.subscribedTopics }
            .sortedByDescending { lags[it.groupId]?.totalLag ?: 0L }
            .take(MAX_CONSUMER_GROUPS)
            .toList()

    /** chain 数组 JSON（纯函数，离线可测；节点结构见类 KDoc）。 */
    internal fun chainJson(
        bootstrap: String,
        topic: KafkaTopicInfo,
        consumers: List<KafkaGroupSnapshot>,
        lags: Map<String, KafkaGroupLagSnapshot>,
    ): String = buildJsonArray {
        // 集群节点：无独立属性可给，bootstrap 本身就是身份（连接配置快照，非敏感）。
        add(
            buildJsonObject {
                put("name", bootstrap)
                put("type", "CLUSTER")
                put("isTarget", false)
                put("relationToNext", REL_HOSTS)
                put("attributes", buildJsonObject { put("bootstrapServers", bootstrap) })
            },
        )
        add(
            buildJsonObject {
                put("name", topic.name)
                put("type", "TOPIC")
                put("isTarget", true)
                if (consumers.isNotEmpty()) put("relationToNext", REL_CONSUMED_BY)
                put(
                    "attributes",
                    buildJsonObject {
                        put("partitions", topic.partitions)
                        put("replicas", topic.replicas)
                        put("internal", topic.internal)
                    },
                )
            },
        )
        consumers.forEach { group ->
            add(
                buildJsonObject {
                    put("name", group.groupId)
                    put("type", "CONSUMER_GROUP")
                    put("isTarget", false)
                    put(
                        "attributes",
                        buildJsonObject {
                            put("state", group.state)
                            put("members", group.members)
                            put("simple", group.simple)
                            put("totalLag", lags[group.groupId]?.totalLag ?: 0L)
                            // 该 topic 的分区级 lag（提交位点 / 末端 / 差值），截到上限。
                            put("topicPartitionLags", buildJsonArray {
                                lags[group.groupId]?.partitions
                                    ?.filter { it.topic == topic.name }
                                    ?.take(MAX_LAG_ROWS)
                                    ?.forEach { p ->
                                        add(
                                            buildJsonObject {
                                                put("partition", p.partition)
                                                put("currentOffset", p.currentOffset)
                                                put("latestOffset", p.latestOffset)
                                                put("lag", p.lag)
                                            },
                                        )
                                    }
                            })
                        },
                    )
                },
            )
        }
    }.toString()
}

/**
 * 「询问 AI」在 Topic 卡片上的按钮构造：**图标区的第一个位置**（AI 绝对最左，
 * 与 ibm-mq 各面板的 actions 拼接顺序一致），tooltip 用 `kafka_action_ask_ai`。
 */
internal fun askAiCardAction(onAskAi: () -> Unit): CardAction = CardAction(
    id = "kafka.ask_ai",
    icon = Icons.Default.Android,
    tooltip = "kafka_action_ask_ai",
    onClick = onAskAi,
)
