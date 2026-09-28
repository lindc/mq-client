package com.mqclient.ibmmq.topology

import com.ibm.mq.constants.MQConstants
import com.mqclient.core.event.EventBus
import com.mqclient.core.event.EventBusErrorReporter
import com.mqclient.graph.topology.EdgeStyle
import com.mqclient.graph.topology.NodeColor
import com.mqclient.graph.topology.NodeStatus
import com.mqclient.graph.topology.TopologyGraph
import com.mqclient.graph.topology.emptyGraph
import com.mqclient.ibmmq.event.IbmMQEvents
import com.mqclient.ibmmq.pcf.MqObjectSnapshot
import com.mqclient.ibmmq.pcf.MqObjectType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * IbmMQTopologyProvider 的单元测试：用 [FakeSource] 替换真实缓存（即「mock 缓存」），
 * 不连 MQ、不依赖 IbmMQModule 单例，纯校验「快照 → 通用拓扑图」的映射与展开逻辑。
 */
class IbmMQTopologyProviderTest {

    private fun queue(
        name: String,
        qType: Int,
        usage: Int = 0,
        currentDepth: Int? = null,
        extra: Map<String, Any?> = emptyMap(),
    ) = MqObjectSnapshot(
        name = name,
        type = MqObjectType.QUEUE,
        currentDepth = currentDepth,
        attributes = mapOf("MQIA_Q_TYPE" to qType, "MQIA_USAGE" to usage) + extra,
    )

    private fun alias(name: String, target: String, baseType: Int = MQConstants.MQOT_Q) =
        queue(
            name, MQConstants.MQQT_ALIAS,
            // MQ 9.3+/10 的 INQUIRE_Q 响应不带 TARGQ，目标经 BASE_OBJECT_NAME 携带
            extra = mapOf("MQCA_BASE_OBJECT_NAME" to target, "MQIA_BASE_TYPE" to baseType),
        )

    private fun remote(name: String, xmitq: String, remoteQmgr: String? = null) =
        queue(
            name, MQConstants.MQQT_REMOTE,
            extra = mapOf("MQCA_XMIT_Q_NAME" to xmitq) +
                (remoteQmgr?.let { mapOf("MQCA_REMOTE_Q_MGR_NAME" to it) } ?: emptyMap()),
        )

    private fun xmitq(name: String) =
        queue(name, MQConstants.MQQT_LOCAL, usage = MQConstants.MQUS_TRANSMISSION)

    private fun channel(
        name: String,
        xmitq: String,
        status: String? = null,
        channelType: String? = null,
    ) = MqObjectSnapshot(
        name = name,
        type = MqObjectType.CHANNEL,
        channelStatus = status,
        channelType = channelType,
        // 通道族属性用 MQCACH_ 前缀（XMITQ 实测键）
        attributes = mapOf("MQCACH_XMIT_Q_NAME" to xmitq),
    )

    private fun listener(name: String, port: Int? = null) =
        MqObjectSnapshot(name = name, type = MqObjectType.LISTENER, port = port)

    private fun topic(name: String) =
        MqObjectSnapshot(name = name, type = MqObjectType.TOPIC)

    private fun subscription(
        name: String,
        topicName: String?,
        topicStr: String? = null,
        dest: String? = null,
    ) = MqObjectSnapshot(
        name = name,
        type = MqObjectType.SUBSCRIPTION,
        attributes = buildMap {
            topicName?.let { put("MQCA_TOPIC_NAME", it) }
            topicStr?.let { put("MQCA_TOPIC_STRING", it) }
            dest?.let { put("MQCACF_DESTINATION", it) }
        },
    )

    @Test
    fun `returns empty graph when not connected`() {
        val provider = IbmMQTopologyProvider(FakeSource(connected = false))
        val graph = runBlocking { provider.buildGraph("c1", "ANY", "queue", depth = 2) }
        assertEquals(emptyGraph("c1"), graph)
    }

    @Test
    fun `excludes SYSTEM and AMQ system objects`() {
        val provider = IbmMQTopologyProvider(
            FakeSource(
                // 业务对象
                queues = listOf(
                    queue("APP.Q", MQConstants.MQQT_LOCAL, currentDepth = 3),
                    alias("ALIAS.APP", "APP.Q"),
                    // 系统对象（应被过滤）
                    queue("SYSTEM.ADMIN.COMMAND.QUEUE", MQConstants.MQQT_LOCAL),
                    queue("AMQ.OUTBOUND.Q", MQConstants.MQQT_LOCAL),
                ),
                channels = listOf(
                    // 系统通道（应被过滤）
                    channel("SYSTEM.DEF.SVRCONN", xmitq = "XMIT.Q", status = "RUNNING"),
                ),
            ),
        )
        // 全量模式（rootName 为空）应只保留业务对象
        val graph = runBlocking { provider.buildGraph("c1", "", "", depth = 2) }

        val names = graph.nodes.map { it.name }.toSet()
        assertEquals(setOf("APP.Q", "ALIAS.APP"), names)
        assertTrue("SYSTEM.* 不应出现在拓扑里" ) { "SYSTEM.ADMIN.COMMAND.QUEUE" !in names }
        assertTrue("AMQ.* 不应出现在拓扑里") { "AMQ.OUTBOUND.Q" !in names }
        assertTrue("SYSTEM.* 通道不应出现在拓扑里") { "SYSTEM.DEF.SVRCONN" !in names }
        // 业务链路仍在：别名 → 业务队列（无向边，不校验方向）
        assertEquals(1, graph.edges.size)
        assertEquals(setOf("ALIAS.APP", "APP.Q"), graph.edges.first().let { setOf(it.from, it.to) })
    }

    @Test
    fun `refreshes immediately when already connected`() = runBlocking {
        val scans = mutableListOf<String>()
        val source = object : IbmMqTopologySource {
            override fun isConnected(connectionId: String) = true
            override fun snapshots(connectionId: String, type: MqObjectType) = emptyList<MqObjectSnapshot>()
            override fun queueManager(connectionId: String): MqObjectSnapshot? = null
            override suspend fun scan(connectionId: String) { scans.add(connectionId) }
        }
        val provider = IbmMQTopologyProvider(source)
        provider.refresh("c1")
        // 已连接：refresh 立即触发一次扫描，无需等待任何事件。
        assertEquals(listOf("c1"), scans)
        provider.dispose()
    }

    @Test
    fun `waits for connect then scans immediately`() = runBlocking {
        val bus = EventBus(this, EventBusErrorReporter.Logging)
        val scanned = CompletableDeferred<Unit>()
        val scans = mutableListOf<String>()
        val source = object : IbmMqTopologySource {
            override fun isConnected(connectionId: String) = false
            override fun snapshots(connectionId: String, type: MqObjectType) = emptyList<MqObjectSnapshot>()
            override fun queueManager(connectionId: String): MqObjectSnapshot? = null
            override suspend fun scan(connectionId: String) {
                scans.add(connectionId)
                scanned.complete(Unit)
            }
        }
        val provider = IbmMQTopologyProvider(source, bus)
        provider.refresh("c1")
        // 未连接：不应立刻扫描，只记入 pending。
        assertTrue(scans.isEmpty(), "未连接时不应立刻扫描")

        // 模拟连接建立：应绕过 5s 起步延迟立即扫描。
        bus.emit(IbmMQEvents.CONNECTED, mapOf("connectionId" to "c1"))
        withTimeout(2000) { scanned.await() }
        assertEquals(listOf("c1"), scans)

        // 其它连接建立不应触发本连接扫描。
        val scannedOther = CompletableDeferred<Unit>()
        bus.emit(IbmMQEvents.CONNECTED, mapOf("connectionId" to "other"))
        assertFalse(scannedOther.isCompleted, "不应为其它连接触发扫描")

        provider.dispose()
    }

    @Test
    fun `immediate scan notifies UI via dataChanged after objects scanned`() = runBlocking {
        val bus = EventBus(this, EventBusErrorReporter.Logging)
        // 真实的 IbmMqServiceTopologySource.scan 走 IbmMQService.scanObjects，完成后发出
        // OBJECTS_SCANNED；这里让假 source 在 scan 时模拟该事件，端到端校验「扫描完成 → UI 重建」。
        val source = object : IbmMqTopologySource {
            override fun isConnected(connectionId: String) = true
            override fun snapshots(connectionId: String, type: MqObjectType) = emptyList<MqObjectSnapshot>()
            override fun queueManager(connectionId: String): MqObjectSnapshot? = null
            override suspend fun scan(connectionId: String) {
                bus.emit(IbmMQEvents.OBJECTS_SCANNED, mapOf("connectionId" to connectionId))
            }
        }
        val provider = IbmMQTopologyProvider(source, bus)
        val changed = CompletableDeferred<String>()
        // UNDISPATCHED：让收集协程在 refresh 之前就同步进入 collect，成为 dataChanged 的活跃订阅者。
        // _dataChanged 是 replay=0 的 SharedFlow，若发射时还没有订阅者，那次发射会被直接丢弃，导致下面
        // 永远等不到；UNDISPATCHED 保证订阅先于 refresh 内 scan 触发的 OBJECTS_SCANNED 发射。
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            provider.dataChanged.collect { changed.complete(it) }
        }
        // 已连接：refresh 立即扫一次；扫完经 OBJECTS_SCANNED → dataChanged 通知面板重建图。
        provider.refresh("c1")
        val cid = withTimeout(2000) { changed.await() }
        assertEquals("c1", cid)
        collector.cancel()
        provider.dispose()
    }

    @Test
    fun `alias queue expands to its target`() {
        val local = queue("LOCAL.Q", MQConstants.MQQT_LOCAL, currentDepth = 5)
        val provider = IbmMQTopologyProvider(
            FakeSource(queues = listOf(alias("ALIAS.LOCAL", "LOCAL.Q"), local)),
        )
        val graph = runBlocking { provider.buildGraph("c1", "ALIAS.LOCAL", "alias_queue", depth = 1) }

        assertEquals(setOf("ALIAS.LOCAL", "LOCAL.Q"), graph.nodes.map { it.name }.toSet())
        assertEquals("alias_queue", graph.nodes.single { it.name == "ALIAS.LOCAL" }.type)
        val localNode = graph.nodes.single { it.name == "LOCAL.Q" }
        assertEquals("queue", localNode.type)
        assertEquals("depth=5", localNode.badge)
        assertEquals(1, graph.edges.size)
    }

    @Test
    fun `remote queue expands through xmitq to channel`() {
        val provider = IbmMQTopologyProvider(
            FakeSource(
                queues = listOf(
                    remote("REMOTE.OUT", "XMIT.Q", "REMOTE.QMGR"),
                    xmitq("XMIT.Q"),
                ),
                channels = listOf(
                    channel("SENDER.CHANNEL", "XMIT.Q", status = "RUNNING", channelType = "SENDER"),
                ),
            ),
        )
        val graph = runBlocking { provider.buildGraph("c1", "REMOTE.OUT", "remote_queue", depth = 2) }

        assertEquals(
            setOf("REMOTE.OUT", "XMIT.Q", "SENDER.CHANNEL"),
            graph.nodes.map { it.name }.toSet(),
        )
        val chan = graph.nodes.single { it.name == "SENDER.CHANNEL" }
        assertEquals("channel", chan.type)
        assertEquals(NodeStatus.RUNNING, chan.status)
        assertEquals(NodeColor.NORMAL, chan.color) // 非 root、非异常
        assertEquals(2, graph.edges.size)
        assertTrue(graph.edges.all { it.style == EdgeStyle.DASHED })
    }

    @Test
    fun `expansion is bidirectional upstream`() {
        val local = queue("LOCAL.Q", MQConstants.MQQT_LOCAL)
        val provider = IbmMQTopologyProvider(
            FakeSource(queues = listOf(alias("ALIAS.LOCAL", "LOCAL.Q"), local)),
        )
        // 从本地队列出发、depth=1，应反向找到指向它的别名队列
        val graph = runBlocking { provider.buildGraph("c1", "LOCAL.Q", "queue", depth = 1) }
        assertEquals(setOf("LOCAL.Q", "ALIAS.LOCAL"), graph.nodes.map { it.name }.toSet())
    }

    @Test
    fun `excludes queue manager and listener nodes`() {
        val qmgr = MqObjectSnapshot(name = "QM1", type = MqObjectType.QUEUE_MANAGER)
        val provider = IbmMQTopologyProvider(
            FakeSource(
                qmgr = qmgr,
                queues = listOf(queue("APP.Q", MQConstants.MQQT_LOCAL)),
                listeners = listOf(listener("LISTENER.1", port = 1414)),
            ),
        )
        val graph = runBlocking { provider.buildGraph("c1", "", "", depth = 2) }

        // QMGR / Listener 不进图（属性在对象面板单独看），只留业务队列
        assertEquals(setOf("APP.Q"), graph.nodes.map { it.name }.toSet())
        assertTrue(graph.nodes.none { it.type == "qmgr" || it.type == "listener" })
        assertEquals(0, graph.edges.size)
    }

    @Test
    fun `alias queue links to its topic target`() {
        val provider = IbmMQTopologyProvider(
            FakeSource(
                queues = listOf(
                    alias("ALIAS.PAY", "PAY.TOPIC", baseType = MQConstants.MQOT_TOPIC),
                ),
                topics = listOf(topic("PAY.TOPIC")),
            ),
        )
        val graph = runBlocking { provider.buildGraph("c1", "", "", depth = 2) }

        assertEquals(setOf("ALIAS.PAY", "PAY.TOPIC"), graph.nodes.map { it.name }.toSet())
        assertEquals(1, graph.edges.size)
        assertEquals(setOf("ALIAS.PAY", "PAY.TOPIC"), graph.edges.first().let { setOf(it.from, it.to) })
    }

    @Test
    fun `subscription links to its destination queue`() {
        val provider = IbmMQTopologyProvider(
            FakeSource(
                topics = listOf(topic("TOPIC.A")),
                subscriptions = listOf(subscription("SUB.1", topicName = "TOPIC.A", dest = "ORDER.LOCAL.Q")),
                queues = listOf(queue("ORDER.LOCAL.Q", MQConstants.MQQT_LOCAL, currentDepth = 2)),
            ),
        )
        val graph = runBlocking { provider.buildGraph("c1", "", "", depth = 2) }

        assertEquals(
            setOf("TOPIC.A", "SUB.1", "ORDER.LOCAL.Q"),
            graph.nodes.map { it.name }.toSet(),
        )
        assertEquals(2, graph.edges.size)
        val pairs = graph.edges.map { setOf(it.from, it.to) }
        assertTrue(pairs.contains(setOf("TOPIC.A", "SUB.1")))
        assertTrue(pairs.contains(setOf("SUB.1", "ORDER.LOCAL.Q")))
    }

    @Test
    fun `topic falls back to topic string match when subscription has no topic name`() {
        val shippingTopic = MqObjectSnapshot(
            name = "SHIPPING",
            type = MqObjectType.TOPIC,
            attributes = mapOf("MQCA_TOPIC_STRING" to "shipping"),
        )
        val provider = IbmMQTopologyProvider(
            FakeSource(
                topics = listOf(shippingTopic),
                // 无 MQCA_TOPIC_NAME（省缺），只有 TOPICSTR；尾部 '/' 应被容忍
                subscriptions = listOf(subscription("SUB.1", topicName = null, topicStr = "shipping/")),
            ),
        )
        val graph = runBlocking { provider.buildGraph("c1", "", "", depth = 2) }

        assertEquals(setOf("SHIPPING", "SUB.1"), graph.nodes.map { it.name }.toSet())
        assertEquals(1, graph.edges.size)
    }

    @Test
    fun `topic expands to its subscriptions`() {
        val provider = IbmMQTopologyProvider(
            FakeSource(
                topics = listOf(topic("TOPIC.A")),
                subscriptions = listOf(subscription("SUB.1", "TOPIC.A")),
            ),
        )
        val graph = runBlocking { provider.buildGraph("c1", "TOPIC.A", "topic", depth = 1) }

        assertEquals(setOf("TOPIC.A", "SUB.1"), graph.nodes.map { it.name }.toSet())
        assertEquals("topic", graph.nodes.single { it.name == "TOPIC.A" }.type)
        assertEquals("subscription", graph.nodes.single { it.name == "SUB.1" }.type)
        assertEquals(1, graph.edges.size)
    }

    @Test
    fun `subscription expands upstream to its topic`() {
        val provider = IbmMQTopologyProvider(
            FakeSource(
                topics = listOf(topic("TOPIC.A")),
                subscriptions = listOf(subscription("SUB.1", "TOPIC.A")),
            ),
        )
        val graph = runBlocking { provider.buildGraph("c1", "SUB.1", "subscription", depth = 1) }
        assertEquals(setOf("SUB.1", "TOPIC.A"), graph.nodes.map { it.name }.toSet())
    }

    @Test
    fun `depth=2 expands two layers while depth=1 stops after one`() {
        val provider = IbmMQTopologyProvider(
            FakeSource(
                queues = listOf(
                    remote("REMOTE.OUT", "XMIT.Q", "REMOTE.QMGR"),
                    xmitq("XMIT.Q"),
                    alias("ALIAS.LOCAL", "LOCAL.Q"),
                    queue("LOCAL.Q", MQConstants.MQQT_LOCAL),
                ),
                channels = listOf(
                    channel("SENDER.CHANNEL", "XMIT.Q", status = "RUNNING", channelType = "SENDER"),
                ),
            ),
        )

        // depth=1：只拿到直接下游（remote → xmitq）
        val oneLayer = runBlocking { provider.buildGraph("c1", "REMOTE.OUT", "remote_queue", depth = 1) }
        assertEquals(setOf("REMOTE.OUT", "XMIT.Q"), oneLayer.nodes.map { it.name }.toSet())
        assertEquals(1, oneLayer.edges.size)

        // depth=2：再向外扩一层（xmitq → channel），共三层节点、两条边
        val twoLayers = runBlocking { provider.buildGraph("c1", "REMOTE.OUT", "remote_queue", depth = 2) }
        assertEquals(
            setOf("REMOTE.OUT", "XMIT.Q", "SENDER.CHANNEL"),
            twoLayers.nodes.map { it.name }.toSet(),
        )
        assertEquals(2, twoLayers.edges.size)

        // 与 root 同组件之外的另一条 alias→local 链不应被拉入
        assertTrue(twoLayers.nodes.none { it.name == "ALIAS.LOCAL" || it.name == "LOCAL.Q" })
    }

    /** 假数据来源：直接返回预置的快照，完全替代真实缓存。 */
    private class FakeSource(
        private val connected: Boolean = true,
        private val queues: List<MqObjectSnapshot> = emptyList(),
        private val channels: List<MqObjectSnapshot> = emptyList(),
        private val listeners: List<MqObjectSnapshot> = emptyList(),
        private val topics: List<MqObjectSnapshot> = emptyList(),
        private val subscriptions: List<MqObjectSnapshot> = emptyList(),
        private val qmgr: MqObjectSnapshot? = null,
    ) : IbmMqTopologySource {
        override fun isConnected(connectionId: String): Boolean = connected
        override fun snapshots(connectionId: String, type: MqObjectType): List<MqObjectSnapshot> = when (type) {
            MqObjectType.QUEUE -> queues
            MqObjectType.CHANNEL -> channels
            MqObjectType.LISTENER -> listeners
            MqObjectType.TOPIC -> topics
            MqObjectType.SUBSCRIPTION -> subscriptions
            else -> emptyList()
        }
        override fun queueManager(connectionId: String): MqObjectSnapshot? = qmgr
        override suspend fun scan(connectionId: String) = Unit
    }
}
