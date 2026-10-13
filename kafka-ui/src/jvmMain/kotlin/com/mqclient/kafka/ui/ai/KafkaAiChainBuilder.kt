package com.mqclient.kafka.ui.ai

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
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
 * 语义目前只有「集群承载主题」一层 —— chain 就是两个节点：
 *
 *   CLUSTER（bootstrap servers，宿主节点）— hosts → TOPIC（目标，带分区 / 副本属性）
 *
 * 数据口径与 ibm-mq 相同：**只读内存态，不碰 MQ** —— 入参 [KafkaTopicInfo] 来自
 * TopicListPanel 已加载的列表（进入 Tab 时的实时查询产物），集群名来自连接配置快照。
 * Consumer Groups（consumed_by 下游）等真链路等后续阶段有对应查询时再扩展。
 *
 * 产物是 chain **数组**的 JSON（不含 currentTime / userQuestion —— 由 ai-ui 的
 * AiPanelState 包装成完整上下文，见 AiAskRequest 的文档）。
 */
object KafkaAiChainBuilder {

    /** relationToNext 取值（上下文 JSON 契约，见 ai-service 的 chat_history.sq 对 context_json 列的注释）。 */
    private const val REL_HOSTS = "hosts"

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

        val chainJson = buildJsonArray {
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
        }.toString()

        return AiAskRequest(
            connectionId = connectionId,
            objectType = "TOPIC",
            objectName = topic.name,
            targetLabel = topic.name,
            contextJson = chainJson,
        )
    }
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
