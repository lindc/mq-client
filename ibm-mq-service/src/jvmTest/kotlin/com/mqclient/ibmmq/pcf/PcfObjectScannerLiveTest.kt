package com.mqclient.ibmmq.pcf

import com.ibm.mq.MQQueueManager
import com.mqclient.core.ConnectionStore
import com.mqclient.ibmmq.IbmMQConfig
import com.mqclient.ibmmq.ibmMQConfigFromOptions
import com.mqclient.ibmmq.mqProperties
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

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
        if (connections.isEmpty()) {
            println("LIVE SKIP: config.toml 里没有 IBM MQ 连接")
            return@runBlocking
        }
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
            println("LIVE SKIP: 连接失败（QMGR 不在线？）：${t.message}")
            return@runBlocking
        }

        try {
            // 逐类直查（自己兜异常打印）——比 scanAllWith 多全量诊断信息：
            // 哪类失败、什么 reasonCode，测试环境日志框架不落控制台也能看到。
            val scanner = PcfObjectScanner()
            val agent = com.ibm.mq.pcf.PCFMessageAgent(manager)
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

                val qmgr = safely("qmgr") { scanner.scanQueueManagerWith(agent) }
                val queues = safely("queues") { scanner.scanQueuesWith(agent) } ?: emptyList()
                val channels = safely("channels") { scanner.scanChannelsWith(agent) } ?: emptyList()
                val topics = safely("topics") { scanner.scanTopicsWith(agent) } ?: emptyList()
                val subscriptions = safely("subscriptions") { scanner.scanSubscriptionsWith(agent) } ?: emptyList()
                val listeners = safely("listeners") { scanner.scanListenersWith(agent) } ?: emptyList()
                val services = safely("services") { scanner.scanServicesWith(agent) } ?: emptyList()
                val processes = safely("processes") { scanner.scanProcessesWith(agent) } ?: emptyList()
                val namelists = safely("namelists") { scanner.scanNamelistsWith(agent) } ?: emptyList()
                val authInfos = safely("authInfos") { scanner.scanAuthInfosWith(agent) } ?: emptyList()
                val commInfos = safely("commInfos") { scanner.scanCommInfosWith(agent) } ?: emptyList()

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
                            "consumers=${q.consumerCount} type=${q.attributes["MQIA_Q_TYPE"]}",
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
            } finally {
                runCatching { agent.disconnect() }
            }
        } finally {
            runCatching { manager.disconnect() }
        }
    }
}
