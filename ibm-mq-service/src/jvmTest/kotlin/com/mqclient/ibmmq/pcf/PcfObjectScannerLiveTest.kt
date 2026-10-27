package com.mqclient.ibmmq.pcf

import com.ibm.mq.MQQueueManager
import com.ibm.mq.constants.MQConstants
import com.ibm.mq.pcf.PCFException
import com.ibm.mq.pcf.PCFMessage
import com.ibm.mq.pcf.PCFMessageAgent
import com.mqclient.core.ConnectionStore
import com.mqclient.ibmmq.IbmMQConfig
import com.mqclient.ibmmq.ibmMQConfigFromOptions
import com.mqclient.ibmmq.mqProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import com.mqclient.testing.assumeLive

/**
 * 真机验证（需要 ~/.mqclient/config.toml 里有可用的 IBM MQ 连接 + QMGR 在线）：
 * 走 core 的解密加载链路取第一条 IBM MQ 连接，真连后跑 [PcfObjectScanner.scanAllWith]，
 * 打印各类对象数量与样例快照。
 *
 * 没有配置 / 没有网络时打印 SKIP 并通过 —— 不让环境问题污染离线 CI。
 */
class PcfObjectScannerLiveTest {

    @Test
    fun liveScanAllObjectTypes() = runBlocking {
        val connections = ConnectionStore.connections.value.filter { it.serviceId == "ibm-mq" }
        // 无环境 = SKIP（JUnit assumption），不是 PASSED —— 否则离线 CI 上这个测试
        // 永远「绿」，等于没跑。assumption 必须放在 try 之外：假设失败抛的是
        // AssumptionViolatedException（RuntimeException），会被下面的宽 catch 吞掉。
        assumeLive("LIVE SKIP: config.toml 里没有 IBM MQ 连接", connections.isNotEmpty())
        val info = connections.first()
        val config: IbmMQConfig = ibmMQConfigFromOptions(info.options)
        println("LIVE connect: name=${info.name} qmgr=${config.queueManager} host=${config.host}:${config.port} channel=${config.channel}")

        val manager = try {
            // 连接阶段同样套 connectionTimeout 超时（与生产 connect 一致），
            // QMGR 掉线时不至于把测试挂十几分钟。
            kotlinx.coroutines.withTimeout(config.connectionTimeout * 1_000L) {
                MQQueueManager(config.queueManager, mqProperties(config))
            }
        } catch (t: Throwable) {
            assumeLive("LIVE SKIP: 连接失败（QMGR 不在线？）：${t.message}", false)
            @Suppress("UNREACHABLE_CODE")
            throw t
        }

        try {
            // 逐类直查（自己兜异常打印）——比 scanAllWith 多全量诊断信息：
            // 哪类失败、什么 reasonCode，测试环境日志框架不落控制台也能看到。
            val scanner = PcfObjectScanner()
            val session = ScanSession(com.ibm.mq.pcf.PCFMessageAgent(manager))
            try {
                suspend fun <T> safely(name: String, block: suspend () -> T): T? = try {
                    block()
                } catch (e: com.ibm.mq.pcf.PCFException) {
                    println("LIVE FAIL $name: reasonCode=${e.reasonCode} completionCode=${e.completionCode} message=${e.message}")
                    null
                } catch (e: Throwable) {
                    println("LIVE FAIL $name: ${e::class.simpleName}: ${e.message}")
                    null
                }

                val qmgr = safely("qmgr") { scanner.scanQueueManagerWith(session) }
                val queues = safely("queues") { scanner.scanQueuesWith(session) } ?: emptyList()
                val channels = safely("channels") { scanner.scanChannelsWith(session) } ?: emptyList()
                val topics = safely("topics") { scanner.scanTopicsWith(session) } ?: emptyList()
                val subscriptions = safely("subscriptions") { scanner.scanSubscriptionsWith(session) } ?: emptyList()
                val listeners = safely("listeners") { scanner.scanListenersWith(session) } ?: emptyList()
                val services = safely("services") { scanner.scanServicesWith(session) } ?: emptyList()
                val processes = safely("processes") { scanner.scanProcessesWith(session) } ?: emptyList()
                val namelists = safely("namelists") { scanner.scanNamelistsWith(session) } ?: emptyList()
                val authInfos = safely("authInfos") { scanner.scanAuthInfosWith(session) } ?: emptyList()
                val commInfos = safely("commInfos") { scanner.scanCommInfosWith(session) } ?: emptyList()

                println(
                    "LIVE result: qmgr=${qmgr?.name} " +
                        "queues=${queues.size} channels=${channels.size} " +
                        "topics=${topics.size} subscriptions=${subscriptions.size} " +
                        "listeners=${listeners.size} services=${services.size} " +
                        "processes=${processes.size} namelists=${namelists.size} " +
                        "authInfos=${authInfos.size} commInfos=${commInfos.size}",
                )

                qmgr?.let { q ->
                    println("LIVE qmgr attrs: name=${q.attributes["MQCA_Q_MGR_NAME"]} " +
                        "version=${q.attributes["MQCA_VERSION"]} platform=${q.attributes["MQIA_PLATFORM"]} " +
                        "cmdLevel=${q.attributes["MQIA_COMMAND_LEVEL"]}")
                }
                queues.take(5).forEach { q ->
                    println(
                        "LIVE queue: ${q.name} depth=${q.currentDepth}/${q.maxDepth} " +
                            "consumers=${q.consumerCount} type=${q.attributes["MQIA_Q_TYPE"]} " +
                            "openPids=${q.attributes["MQIACF_OPEN_PROCESS_IDS"]}",
                    )
                }
                channels.take(5).forEach { c ->
                    println("LIVE channel: ${c.name} type=${c.channelType} status=${c.channelStatus}")
                }
                listeners.forEach { l ->
                    println("LIVE listener: ${l.name} status=${l.listenerStatus} port=${l.port}")
                }
                topics.take(5).forEach { t ->
                    println("LIVE topic: ${t.name}")
                }

                // 基线断言：连接成功的前提下，QMGR 属性必须拿到，队列至少有系统队列
                assertTrue(qmgr != null && qmgr.name.isNotBlank(), "QMGR 属性查询失败：见上方 LIVE FAIL 行")
                assertTrue(queues.isNotEmpty(), "队列列表为空——检查通道 / 权限 / 通配符")
                assertTrue(queues.all { it.name.isNotBlank() }, "存在无名队列快照")
                // 回归守卫：INQUIRE_Q 一旦误带 MQIACF_Q_ATTRS，QMGR 只回点名 attr，
                // 快照会塌缩成几行——本地队列必须仍有全量属性（MAX_Q_DEPTH 等）。
                queues.filter { it.attributes["MQIA_Q_TYPE"] == MQConstants.MQQT_LOCAL }
                    .forEach { q ->
                        assertTrue(
                            q.attributes.containsKey("MQIA_MAX_Q_DEPTH"),
                            "${q.name} 快照只剩 ${q.attributes.size} 个 attr——INQUIRE_Q 误带 MQIACF_Q_ATTRS？",
                        )
                    }
            } finally {
                runCatching { session.agent.disconnect() }
            }
        } finally {
            runCatching { manager.disconnect() }
        }
    }

    /**
     * 回归：INQUIRE_Q 显式请求 MQIACF_Q_ATTRS 后，alias 队列快照必须带
     * MQIA_DEF_READ_AHEAD / MQIA_DEF_PUT_RESPONSE_TYPE（属性对话框 Extended 页）。
     * QMGR 对 alias 的默认应答不含这两个 attr，漏请求时 UI 只能少行。
     * 没有配置 / 没有网络 / 无 alias 队列时记 SKIP（assumption），不是 PASSED。
     */
    @Test
    fun aliasQueuesExposeExtendedAttrs() = runBlocking {
        val connections = ConnectionStore.connections.value.filter { it.serviceId == "ibm-mq" }
        assumeLive("LIVE SKIP: config.toml 里没有 IBM MQ 连接", connections.isNotEmpty())
        val info = connections.first()
        val config: IbmMQConfig = ibmMQConfigFromOptions(info.options)
        val manager = try {
            kotlinx.coroutines.withTimeout(config.connectionTimeout * 1_000L) {
                MQQueueManager(config.queueManager, mqProperties(config))
            }
        } catch (t: Throwable) {
            assumeLive("LIVE SKIP: 连接失败（QMGR 不在线？）：${t.message}", false)
            @Suppress("UNREACHABLE_CODE")
            throw t
        }
        try {
            val agent = PCFMessageAgent(manager)
            val session = ScanSession(agent)
            try {
                val aliases = PcfObjectScanner().scanQueuesWith(session)
                    .filter { it.attributes["MQIA_Q_TYPE"] == MQConstants.MQQT_ALIAS }
                assumeLive("LIVE SKIP: 无 alias 队列", aliases.isNotEmpty())
                aliases.forEach { q ->
                    println(
                        "LIVE alias ${q.name}: defReadAhead=${q.attributes["MQIA_DEF_READ_AHEAD"]} " +
                            "defPutResp=${q.attributes["MQIA_DEF_PUT_RESPONSE_TYPE"]}",
                    )
                    assertTrue(
                        q.attributes.containsKey("MQIA_DEF_READ_AHEAD"),
                        "alias ${q.name} 快照缺 MQIA_DEF_READ_AHEAD",
                    )
                    assertTrue(
                        q.attributes.containsKey("MQIA_DEF_PUT_RESPONSE_TYPE"),
                        "alias ${q.name} 快照缺 MQIA_DEF_PUT_RESPONSE_TYPE",
                    )
                }
            } finally {
                runCatching { agent.disconnect() }
            }
        } finally {
            runCatching { manager.disconnect() }
        }
    }

    /**
     * 真机验证「停止监听器」的轮询路径（复刻 UI 的 STOP LISTENER + 状态轮询）：
     * 先 START 让 LSNR.QM1 进入 RUNNING，再 STOP，随后轮询 [PcfObjectScanner.queryListenerStatusWith]，
     * 断言——停止后**不再抛 2085**（MQRC_UNKNOWN_OBJECT_NAME 已被兜底为空结果，即 NOT_RUNNING），
     * 且最终轮询收敛到空结果（监听器已停）。这正是对 2085 误报修复的回归验证。
     *
     * 没有配置 / 没有网络 / 目标监听器不存在时打印 LIVE SKIP 并通过，不让环境问题污染离线 CI。
     */
    @Test
    fun liveStopListenerPoll() = runBlocking {
        val connections = ConnectionStore.connections.value.filter { it.serviceId == "ibm-mq" }
        assumeLive("LIVE SKIP: config.toml 里没有 IBM MQ 连接", connections.isNotEmpty())
        val info = connections.first()
        val config = ibmMQConfigFromOptions(info.options)

        val manager = try {
            withTimeout(config.connectionTimeout * 1_000L) { MQQueueManager(config.queueManager, mqProperties(config)) }
        } catch (t: Throwable) {
            assumeLive("LIVE SKIP: 连接失败（QMGR 不在线？）：${t.message}", false)
            @Suppress("UNREACHABLE_CODE")
            throw t
        }

        val agent = PCFMessageAgent(manager)
        val session = ScanSession(agent)
        try {
            val scanner = PcfObjectScanner()
            val name = "LSNR.QM1"
            assumeLive("LIVE SKIP: $name 不存在", scanner.scanListenersWith(session).any { it.name == name })

            fun runMqsc(cmd: String) {
                val req = PCFMessage(MQConstants.MQCMD_ESCAPE).apply {
                    addParameter(MQConstants.MQIACF_ESCAPE_TYPE, MQConstants.MQET_MQSC)
                    addParameter(MQConstants.MQCACF_ESCAPE_TEXT, cmd)
                }
                agent.send(req)
            }

            // 先确保进入 RUNNING 才开始验证停止：START 后轮询到非空状态。
            runCatching { runMqsc("START LISTENER('$name')") }
            var running = false
            repeat(10) {
                delay(500)
                val s = scanner.queryListenerStatusWith(session, name)
                if (s.isNotEmpty()) { running = true; return@repeat }
            }
            println("LIVE start '$name' running=$running")
            assumeLive("LIVE SKIP: 无法让 $name 进入 RUNNING，跳过停止验证", running)

            // 复刻 UI：发 STOP，命令成功即进入轮询。
            runMqsc("STOP LISTENER('$name')")

            // 轮询：验证不再抛 2085，且最终回到空结果（NOT_RUNNING）。
            var hit2085 = false
            var finalEmpty = false
            repeat(20) {
                delay(1_000)
                val r = try {
                    Result.success(scanner.queryListenerStatusWith(session, name))
                } catch (e: Throwable) {
                    Result.failure<List<Pair<String, Int>>>(e)
                }
                if (r.isSuccess) {
                    val list = r.getOrNull()!!
                    if (list.isEmpty()) { finalEmpty = true; println("LIVE poll#$it: 空（已停 → NOT_RUNNING）"); return@repeat }
                    else println("LIVE poll#$it: $list")
                } else {
                    val e = r.exceptionOrNull()!!
                    val rc = (e as? PCFException)?.reasonCode
                    if (rc == MQConstants.MQRC_UNKNOWN_OBJECT_NAME) hit2085 = true
                    println("LIVE poll#$it: 异常 ${e::class.simpleName} rc=$rc")
                }
            }
            println("LIVE result: hit2085=$hit2085 finalEmpty=$finalEmpty")
            assertTrue(!hit2085, "停止后轮询仍抛 2085，说明 2085 兜底未生效")
            assertTrue(finalEmpty, "停止后 30s 内未收敛到 NOT_RUNNING（空结果）")
            println("LIVE PASS: 停止 LSNR.QM1 后轮询正常收敛到 NOT_RUNNING，无 2085 误报")
        } finally {
            runCatching { agent.disconnect() }
            runCatching { manager.disconnect() }
        }
    }
}
