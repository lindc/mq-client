package com.mqclient.ibmmq.messaging

import com.ibm.mq.MQException
import com.ibm.mq.MQMessage
import com.ibm.mq.MQQueue
import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import com.ibm.mq.pcf.PCFMessage
import com.ibm.mq.pcf.PCFMessageAgent
import com.mqclient.core.ConnectionStore
import com.mqclient.ibmmq.IbmMQConfig
import com.mqclient.ibmmq.IbmMqTls
import com.mqclient.ibmmq.ibmMQConfigFromOptions
import com.mqclient.ibmmq.mqProperties
import com.mqclient.testing.assumeLive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 真机验证浏览会话的性能与交付正确性（需要 `~/.mqclient/config.toml` 里有可用的
 * IBM MQ 连接；没有环境时走 JUnit assumption 记 **skipped**，不是 passed）。
 *
 * ## 守的是什么
 *
 * 旧实现每翻一页都重开队列句柄、从队首扫到上次位置，第 N 页要扫 `(N-1)×页大小` 条，
 * 且整页握着连接的 `mqiLock`（会挡住心跳 / 发送）。改成常驻句柄后，每页成本应与页号
 * 无关。这里用一个自建队列灌 [MESSAGES] 条消息、连续翻 [PAGES] 页，对比首页与末页
 * 的耗时 —— 旧实现下末页约是首页的 10 倍，新实现应在同一量级。
 *
 * 同时校验**交付集合精确等于灌入集合**：不重不漏。删除一条后继续翻页也必须成立
 * （游标按 MessageId 重新定位的那条路径）。
 */
class MessageBrowseSessionLiveTest {

    private companion object {
        const val QUEUE = "MQCLIENT.BROWSE.LIVE"
        const val MESSAGES = 300
        const val PAGE = 30
        const val PAGES = MESSAGES / PAGE

        /** PCF：对象已存在（MQConstants 里没导出这个常量时要自己抬）。 */
        const val MQRCCF_OBJECT_ALREADY_EXISTS = 4001
    }

    @Test
    fun livePagingCostDoesNotGrowWithPageIndex() = runBlocking {
        val (manager, config) = connectOrSkip()
        try {
            resetQueue(manager)
            val putIds = seed(manager, MESSAGES)

            val session = MessageBrowseSession.open(manager, QUEUE, Mutex())
            try {
                val all = mutableListOf<String>()
                val pageMs = mutableListOf<Long>()
                repeat(PAGES) {
                    val t0 = System.currentTimeMillis()
                    val page = session.browseNext(PAGE)
                    pageMs += System.currentTimeMillis() - t0
                    all += page.map { it.messageId }
                    assertEquals(PAGE, page.size, "第 ${it + 1} 页条数应等于页大小")
                }
                val tail = session.browseNext(PAGE)
                assertTrue(tail.isEmpty(), "读完 $MESSAGES 条后应已到队尾，实际还返回 ${tail.size} 条")

                println("LIVE browse pageMs=$pageMs")
                val first = pageMs.first().coerceAtLeast(1)
                val last = pageMs.last()
                assertTrue(
                    last <= first * 3 + 250,
                    "翻页成本不应随页号增长（旧实现是 O(已加载)）：首页 ${first}ms，末页 ${last}ms，全部=$pageMs",
                )
                assertEquals(MESSAGES, all.size, "总条数不符")
                assertEquals(putIds.toSet(), all.toSet(), "交付集合必须与灌入集合一致（不重不漏）")
                assertEquals(all.size, all.toSet().size, "不应有重复交付")
            } finally {
                session.close()
            }
        } finally {
            deleteQueue(manager)
            runCatching { manager.disconnect() }
        }
    }

    @Test
    fun liveDeletedMessageDoesNotCauseLossOrDuplicates() = runBlocking {
        val (manager, config) = connectOrSkip()
        try {
            resetQueue(manager)
            val putIds = seed(manager, MESSAGES)
            val session = MessageBrowseSession.open(manager, QUEUE, Mutex())
            try {
                val firstPage = session.browseNext(PAGE)
                val victim = firstPage[PAGE / 2]
                val removed = deleteMessageById(manager, QUEUE, victim.messageId)
                assertTrue(removed, "真机删除应按 MessageId 命中并消费该条")
                // UI 删除成功后正是这么通知会话的（见 MessageBrowseTab.deleteMessage）。
                session.onMessageRemoved(victim.messageId)

                val rest = mutableListOf<String>()
                while (true) {
                    val page = session.browseNext(PAGE)
                    if (page.isEmpty()) break
                    rest += page.map { it.messageId }
                    if (rest.size > MESSAGES) break
                }
                val delivered = firstPage.map { it.messageId } + rest
                println(
                    "LIVE delete-then-page: delivered=${delivered.size} put=${putIds.size} " +
                        "duplicates=${delivered.size - delivered.toSet().size}",
                )
                // 被删的那条在删除**之前**已经在第一页展示过，所以它仍属于「已交付」；
                // 删除后要保证的是「不再重复交付它」且「其它的一个不少」——
                // 即全部 $MESSAGES 条恰好各交付一次。
                assertEquals(
                    putIds,
                    delivered.toSet(),
                    "删除一条后继续翻页：其余消息不能遗漏，被删的那条也不能重新出现",
                )
                assertEquals(delivered.size, delivered.toSet().size, "不应重复交付")
            } finally {
                session.close()
            }
        } finally {
            deleteQueue(manager)
            runCatching { manager.disconnect() }
        }
    }

    /* ------------------------------ 环境与夹具 ------------------------------ */

    private fun connectOrSkip(): Pair<MQQueueManager, IbmMQConfig> = runBlocking {
        val connections = ConnectionStore.connections.value.filter { it.serviceId == "ibm-mq" }
        assumeLive("LIVE SKIP: config.toml 里没有 IBM MQ 连接", connections.isNotEmpty())
        val info = connections.first()
        val config = ibmMQConfigFromOptions(info.options)
        println("LIVE connect: name=${info.name} qmgr=${config.queueManager} host=${config.host}:${config.port}")

        val manager = try {
            // 与生产连接同一组合（TLS 窗口 + mqProperties），否则配了 TLS 的 QM 连不上。
            withTimeout(config.connectionTimeout * 1_000L) {
                IbmMqTls.withTls(config) { MQQueueManager(config.queueManager, mqProperties(config)) }
            }
        } catch (t: Throwable) {
            assumeLive("LIVE SKIP: 连接失败（QMGR 不在线？）：${t.message}", false)
            @Suppress("UNREACHABLE_CODE")
            throw t
        }
        manager to config
    }

    /**
     * 保证测试队列可用且为空。
     *
     * 不依赖「先删再建」：会话的 [MessageBrowseSession.close] 是**异步**关句柄的
     * （不能在 Compose 的 onDispose 线程上阻塞），上一个用例刚结束时队列可能仍然
     * 处于打开状态，删不掉。所以这里只做「不存在就建」+「清空」，与关闭时机无关。
     */
    private fun resetQueue(manager: MQQueueManager) {
        try {
            admin(manager) { agent ->
                agent.send(
                    PCFMessage(MQConstants.MQCMD_CREATE_Q).apply {
                        addParameter(MQConstants.MQCA_Q_NAME, QUEUE)
                        addParameter(MQConstants.MQIA_Q_TYPE, MQConstants.MQQT_LOCAL)
                    },
                )
            }
        } catch (e: MQException) {
            // 4001 = MQRCCF_OBJECT_ALREADY_EXISTS：上一次运行留下的，可以继续。
            if (e.reasonCode != MQRCCF_OBJECT_ALREADY_EXISTS) {
                assumeLive(
                    "LIVE SKIP: 无权创建测试队列 $QUEUE（需 MQCMD_CREATE_Q 权限）：${e.message}",
                    false,
                )
            }
        }
        drain(manager)
        println("LIVE queue prepared: $QUEUE")
    }

    /** 破坏性取空队列（不依赖 CLEAR/PURGE 命令的额外权限）。 */
    private fun drain(manager: MQQueueManager) {
        var queue: MQQueue? = null
        try {
            queue = manager.accessQueue(
                QUEUE,
                MQConstants.MQOO_INPUT_AS_Q_DEF or MQConstants.MQOO_FAIL_IF_QUIESCING,
            )
            val gmo = com.ibm.mq.MQGetMessageOptions().apply {
                options = MQConstants.MQGMO_NO_WAIT or MQConstants.MQGMO_FAIL_IF_QUIESCING
            }
            var drained = 0
            while (true) {
                try {
                    queue.get(MQMessage(), gmo)
                    drained++
                } catch (e: MQException) {
                    if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) break
                    throw e
                }
            }
            if (drained > 0) println("LIVE drained $drained leftover messages from $QUEUE")
        } catch (e: MQException) {
            assumeLive("LIVE SKIP: 无权清空测试队列 $QUEUE：${e.message}", false)
        } finally {
            runCatching { queue?.close() }
        }
    }

    /**
     * 删除测试队列（best-effort）。
     *
     * 会话关句柄是异步的，删除可能短时间报「对象正在使用」— 重试几次再放弃。
     * 删不掉也不该让测试失败（队列留给下次 resetQueue 复用）。
     */
    private fun deleteQueue(manager: MQQueueManager) {
        repeat(5) { attempt ->
            val ok = runCatching {
                admin(manager) { agent ->
                    agent.send(
                        PCFMessage(MQConstants.MQCMD_DELETE_Q).apply {
                            addParameter(MQConstants.MQCA_Q_NAME, QUEUE)
                        },
                    )
                }
            }.isSuccess
            if (ok) return
            Thread.sleep(200L * (attempt + 1))
        }
    }

    private fun <T> admin(manager: MQQueueManager, block: (PCFMessageAgent) -> T): T {
        val agent = PCFMessageAgent(manager)
        return try {
            block(agent)
        } finally {
            runCatching { agent.disconnect() }
        }
    }

    /** 灌入 [count] 条消息，返回 QMGR 分配的 MessageId 集合（= 浏览应交付的集合）。 */
    private fun seed(manager: MQQueueManager, count: Int): Set<String> {
        val queue = manager.accessQueue(
            QUEUE,
            MQConstants.MQOO_OUTPUT or MQConstants.MQOO_FAIL_IF_QUIESCING,
        )
        val ids = LinkedHashSet<String>()
        try {
            val options = com.ibm.mq.MQPutMessageOptions()
            repeat(count) { index ->
                val message = MQMessage().apply {
                    // 明确不指定 MessageId，让 QMGR 生成 —— 与真实生产路径一致。
                    messageId = MQConstants.MQMI_NONE
                    writeString("browse-live-payload-%04d".format(index))
                }
                queue.put(message, options)
                ids += message.messageId.toMqIdHex()
            }
        } finally {
            runCatching { queue.close() }
        }
        println("LIVE seeded $count messages into $QUEUE")
        return ids
    }

    /**
     * 按 MessageId 消费一条（与生产 `MessageAccess.deleteMessageById` 同一手法：
     * BROWSE 定位后 `MQGMO_MSG_UNDER_CURSOR` 破坏性 get，只动这一条）。
     */
    private fun deleteMessageById(manager: MQQueueManager, queueName: String, targetHex: String): Boolean {
        var queue: MQQueue? = null
        try {
            queue = manager.accessQueue(
                queueName,
                MQConstants.MQOO_BROWSE or
                    MQConstants.MQOO_INPUT_AS_Q_DEF or
                    MQConstants.MQOO_FAIL_IF_QUIESCING,
            )
            val gmo = com.ibm.mq.MQGetMessageOptions()
            var first = true
            while (true) {
                gmo.options =
                    (if (first) MQConstants.MQGMO_BROWSE_FIRST else MQConstants.MQGMO_BROWSE_NEXT) or
                        MQConstants.MQGMO_NO_WAIT or MQConstants.MQGMO_FAIL_IF_QUIESCING
                val message = MQMessage()
                try {
                    queue.get(message, gmo)
                } catch (e: MQException) {
                    if (e.reasonCode == MQConstants.MQRC_NO_MSG_AVAILABLE) return false
                    throw e
                }
                first = false
                if (message.messageId.toMqIdHex() == targetHex) {
                    val destructive = com.ibm.mq.MQGetMessageOptions().apply {
                        options = MQConstants.MQGMO_MSG_UNDER_CURSOR or MQConstants.MQGMO_FAIL_IF_QUIESCING
                    }
                    queue.get(MQMessage(), destructive)
                    return true
                }
            }
        } finally {
            runCatching { queue?.close() }
        }
    }
}
