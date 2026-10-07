package com.mqclient.ibmmq.ui.ai

import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.pcf.MqObjectType
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.ui.ChannelIconResolver
import com.mqclient.ibmmq.ui.ChannelType
import com.mqclient.ibmmq.ui.topology.filterNotSystem
import com.mqclient.ibmmq.ui.topology.getAliasTarget
import com.mqclient.ibmmq.ui.topology.getChannelRqmName
import com.mqclient.ibmmq.ui.topology.getChannelXmitQueue
import com.mqclient.ibmmq.ui.topology.getRemoteName
import com.mqclient.ibmmq.ui.topology.getRemoteQueueRqmName
import com.mqclient.ibmmq.ui.topology.getRemoteXmitQueue
import com.mqclient.ibmmq.ui.topology.getSubscriptionDestination
import com.mqclient.ibmmq.ui.topology.isAliasQueue
import com.mqclient.ibmmq.ui.topology.isLocalQueue
import com.mqclient.ibmmq.ui.topology.isModelQueue
import com.mqclient.ibmmq.ui.topology.isRemoteQueue
import com.mqclient.ibmmq.ui.topology.isTransmission
import com.mqclient.ibmmq.ui.topology.isSystemObject
import com.mqclient.uicommon.AiAskRequest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 「询问 AI」链路上下文构建器：从对象慢扫缓存（[IbmMQModule] → cachedObjects，只读、
 * 不碰 MQ）提取目标对象的上下游链路，序列化成发给 AI 的 chain JSON。
 *
 * 与 [com.mqclient.ibmmq.ui.topology.TopologyGraphBuilder] 同源但独立：那边产出 Kuiver
 * 全图（边只有 fromId/toId、无关系语义、绑定外部 UI 库），这里只要「从目标出发 1 层 +
 * 带 relationToNext 的有序节点」。关系规则与拓扑图保持一致（alias→target、remote→xmitq、
 * xmitq→channel 反查、channel→RQMNAME、sub→dest）——第二处同构，按项目去重约定出现
 * 第三处时再抽公共层。
 *
 * 产物是 chain **数组**的 JSON（不含 currentTime / userQuestion——问题在发送时才知道，
 * 由 ai-ui 的 AiPanelState 包装成完整上下文；currentTime 也以发送时刻为准）。
 *
 * 链路线性化说明：JSON 是数组 + relationToNext（与下一节点的关系）。分叉（多个上游同时
 * 指向同一节点）第一版不建分支结构，上游节点依序排列、各自 relationToNext 指向它概念上
 * 的下游（同一节点）——与拓扑图「只画一条主链路」的取舍一致。
 */
object AiChainBuilder {

    /** chain 长度上限：超过时优先保留目标对象与直接上下游（xmitq / 远端 QMgr）。 */
    private const val MAX_NODES = 10

    /** relationToNext 取值（上下文 JSON 契约，见 EventNames.MQ_AI_CONVERSATION_COMPLETED 文档）。 */
    private const val REL_POINTS_TO = "points_to"
    private const val REL_USES_XMITQ = "uses_xmitq"
    private const val REL_TRIGGERS = "triggers"
    private const val REL_SENDS_TO = "sends_to"
    private const val REL_DELIVERS_TO = "delivers_to"

    /**
     * 从缓存构建「询问 AI」请求。
     *
     * @param objectType 目标对象类型（QUEUE / CHANNEL / LISTENER——入口已按此三类过滤）。
     * @param objectName 目标对象名。
     * @return 缓存就绪且目标是合法入口对象时返回请求；服务不可用 / 快照缺失 / 子型不匹配
     *         （如别名队列、RECEIVER 通道）返回 null，调用方忽略即可。
     */
    fun build(connectionId: String, objectType: MqObjectType, objectName: String): AiAskRequest? {
        val service = IbmMQModule.serviceOrNull() ?: return null
        val nodes = when (objectType) {
            MqObjectType.QUEUE -> {
                val target = service.cachedObjects(connectionId, MqObjectType.QUEUE)
                    .firstOrNull { it.name == objectName }?.takeIf { it.isLocalQueue() }
                    ?: return null
                buildQueueChain(connectionId, target)
            }

            MqObjectType.CHANNEL -> {
                val target = service.cachedObjects(connectionId, MqObjectType.CHANNEL)
                    .firstOrNull { it.name == objectName }
                    ?.takeIf { ChannelIconResolver.resolveType(it) == ChannelType.SENDER }
                    ?: return null
                buildChannelChain(connectionId, target)
            }

            MqObjectType.LISTENER -> {
                val target = service.cachedObjects(connectionId, MqObjectType.LISTENER)
                    .firstOrNull { it.name == objectName } ?: return null
                listOf(node(target, "LISTENER", isTarget = true, relationToNext = null))
            }

            else -> return null
        }

        val chainJson = buildJsonArray {
            nodes.forEach { n ->
                add(
                    buildJsonObject {
                        put("name", n.name)
                        put("type", n.type)
                        put("isTarget", n.isTarget)
                        n.relationToNext?.let { put("relationToNext", it) }
                        put("attributes", attributesJson(n.snapshot))
                    },
                )
            }
        }.toString()

        return AiAskRequest(
            connectionId = connectionId,
            objectType = objectType.name,
            objectName = objectName,
            targetLabel = objectName,
            contextJson = chainJson,
        )
    }

    // ---- 节点模型（构建期中间结构，序列化后即弃） ----

    private class ChainNode(
        val snapshot: MqObjectSnapshot?,
        val name: String,
        val type: String,
        val isTarget: Boolean,
        val relationToNext: String?,
    )

    private fun node(
        snapshot: MqObjectSnapshot,
        type: String,
        isTarget: Boolean,
        relationToNext: String?,
    ): ChainNode = ChainNode(snapshot, snapshot.name, type, isTarget, relationToNext)

    // ---- 本地队列 target：上游 = 指向它的 alias / remote / subscription，无下游 ----

    private fun buildQueueChain(connectionId: String, target: MqObjectSnapshot): List<ChainNode> {
        val queues = cachedQueues(connectionId)
        val subs = cached(connectionId, MqObjectType.SUBSCRIPTION)
        val localQmgrName = localQmgrName(connectionId)

        val upstreamAliases = queues.filter { it.isAliasQueue() && it.getAliasTarget() == target.name }
        val upstreamRemotes = queues.filter {
            // QREMOTE 指向本地队列的形态：RNAME 相符且 RQMNAME 为空或就是本 QMgr
            // （指向远端的是出站定义，与本队列无直接边）。
            if (!it.isRemoteQueue() || it.getRemoteName() != target.name) return@filter false
            val rqm = it.getRemoteQueueRqmName()
            rqm.isNullOrBlank() || rqm == localQmgrName
        }
        val upstreamSubs = subs.filter { it.getSubscriptionDestination() == target.name }

        val upstream = buildList {
            upstreamAliases.forEach { add(node(it, "ALIAS_QUEUE", isTarget = false, relationToNext = REL_POINTS_TO)) }
            upstreamRemotes.forEach { add(node(it, "REMOTE_QUEUE", isTarget = false, relationToNext = REL_POINTS_TO)) }
            upstreamSubs.forEach { add(node(it, "SUBSCRIPTION", isTarget = false, relationToNext = REL_DELIVERS_TO)) }
        }

        // 预算 = MAX_NODES - target（1）：上游超预算时丢最远上游（列表头）。
        return truncate(upstream, reservedSlots = 1) +
            node(target, "QUEUE", isTarget = true, relationToNext = null)
    }

    // ---- 发送方通道 target：alias→remote→xmitq→[通道]→远端 QMgr ----

    private fun buildChannelChain(connectionId: String, target: MqObjectSnapshot): List<ChainNode> {
        val queues = cachedQueues(connectionId)
        val aliasQueues = queues.filter { it.isAliasQueue() }
        val remoteQueues = queues.filter { it.isRemoteQueue() }
        val xmitqName = target.getChannelXmitQueue()
        val xmitq = xmitqName?.let { x -> queues.filter { it.isTransmission() }.firstOrNull { it.name == x } }
        val remotes = remoteQueues.filter { xmitqName != null && it.getRemoteXmitQueue() == xmitqName }
        val aliases = aliasQueues.filter { a -> remotes.any { a.getAliasTarget() == it.name } }

        val rqmgrName = channelRemoteQmgr(connectionId, target)
        val localQmgrName = localQmgrName(connectionId)
        // 远端 QMgr 虚拟节点：无快照无属性（名字来自通道 RQMNAME / 共享 XMITQ 的 QREMOTE 推导）。
        // 指向本地 QMgr 自身的不算「远程」（与拓扑图同规则）。
        val rqmgrNode = rqmgrName
            ?.takeIf { it.isNotBlank() && it != localQmgrName }
            ?.let { ChainNode(null, it, "QMGR", isTarget = false, relationToNext = null) }

        // 顺序（上游 → 下游）：aliases、remotes、xmitq、[通道 target]、远端 QMgr。
        // 预算 = MAX_NODES - target（1）- QMgr（1）：超预算丢最远上游（aliases 在列表头，先丢）。
        val upstream = buildList {
            aliases.forEach { add(node(it, "ALIAS_QUEUE", isTarget = false, relationToNext = REL_POINTS_TO)) }
            remotes.forEach { add(node(it, "REMOTE_QUEUE", isTarget = false, relationToNext = REL_USES_XMITQ)) }
            xmitq?.let { add(node(it, "XMITQ", isTarget = false, relationToNext = REL_TRIGGERS)) }
        }

        val nodes = mutableListOf<ChainNode>()
        nodes += truncate(upstream, reservedSlots = if (rqmgrNode != null) 2 else 1)
        nodes += node(
            target,
            "CHANNEL",
            isTarget = true,
            // 通道有下游时 relationToNext = sends_to（指向远端 QMgr）；QMgr 名缺失（理论上
            // SENDER 通道总有，兜底）或指向本地时 chain 止于通道，无下游关系。
            relationToNext = if (rqmgrNode != null) REL_SENDS_TO else null,
        )
        rqmgrNode?.let(nodes::add)
        return nodes
    }

    /**
     * 通道的远端 QMgr 名：优先通道自身 RQMNAME；实测部分 MQ（含 MQ 9.x 沙箱）的
     * INQUIRE_CHANNEL 不回该参数时，经「共享同一 XMITQ 的 QREMOTE 所带的 RQMNAME」
     * 兜底推导——QREMOTE 定义实测恒带 RQMNAME（与拓扑图同源）。
     */
    private fun channelRemoteQmgr(connectionId: String, channel: MqObjectSnapshot): String? {
        channel.getChannelRqmName()?.let { return it }
        val rqmByXmitq = cachedQueues(connectionId).filter { it.isRemoteQueue() }
            .mapNotNull { rq ->
                rq.getRemoteQueueRqmName()?.takeIf { it.isNotBlank() }?.let { rq.getRemoteXmitQueue() to it }
            }.toMap()
        return rqmByXmitq[channel.getChannelXmitQueue()]
    }

    // ---- 上游截断：超出预算时从列表头（最远上游）开始丢 ----

    private fun truncate(upstream: List<ChainNode>, reservedSlots: Int): List<ChainNode> {
        val budget = MAX_NODES - reservedSlots
        return if (upstream.size > budget) upstream.drop(upstream.size - budget) else upstream
    }

    // ---- attributes 序列化 ----

    /**
     * 全属性直出（不筛选），值类型：Boolean / Number 原样，其余 toString。安全兜底：
     * 键名含 PASSWORD / KEY 的条目剔除——PCF INQUIRE 本不返回密钥，此为双保险（防未来
     * 扩展把敏感配置混进 attributes）。
     */
    private fun attributesJson(snapshot: MqObjectSnapshot?): kotlinx.serialization.json.JsonObject {
        if (snapshot == null) return buildJsonObject { }
        return buildJsonObject {
            snapshot.attributes.forEach { (key, value) ->
                val k = key.uppercase()
                if ("PASSWORD" in k || "KEY" in k) return@forEach
                when (value) {
                    null -> return@forEach
                    is Boolean -> put(key, value)
                    is Number -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }
    }

    // ---- 缓存读取 ----

    private fun cached(connectionId: String, type: MqObjectType): List<MqObjectSnapshot> =
        IbmMQModule.serviceOrNull()?.cachedObjects(connectionId, type) ?: emptyList()

    private fun cachedQueues(connectionId: String): List<MqObjectSnapshot> =
        cached(connectionId, MqObjectType.QUEUE).filterNotSystem().filterNot { it.isModelQueue() }

    private fun localQmgrName(connectionId: String): String? =
        IbmMQModule.serviceOrNull()?.cachedQueueManager(connectionId)?.name
}
