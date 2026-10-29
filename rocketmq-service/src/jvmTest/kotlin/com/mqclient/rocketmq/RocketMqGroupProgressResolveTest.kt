package com.mqclient.rocketmq

import org.apache.rocketmq.client.exception.MQBrokerException
import org.apache.rocketmq.client.exception.MQClientException
import org.apache.rocketmq.common.message.MessageQueue
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats
import org.apache.rocketmq.remoting.protocol.admin.OffsetWrapper
import org.apache.rocketmq.remoting.protocol.body.ConsumeStatsList
import org.apache.rocketmq.remoting.exception.RemotingTimeoutException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 消费进度的取数策略（[resolveGroupProgress]）：**组级优先，被路由挡住时 broker 直读兜底**。
 *
 * 现场背景（chillbear 集群实测）：
 * - `CG_PROGRESS_PROBE`：组级 CODE 17（`%RETRY%` 无路由），但 broker 上确实有 12 条位点；
 * - `CG_Normal`：组级 CODE 17，broker 上 0 条 —— **真没有**，兜底必须保持空；
 * - `CID_RMQ_SYS_TRANS`：组级直接成功，兜底根本不该被调用。
 *
 * 这三条是这组用例的原型。触发条件最容易写错：放宽到「任何失败都兜底」会把一次
 * 超时放大成 N 次 broker 请求；收紧到「只 17」会漏掉 206。
 */
class RocketMqGroupProgressResolveTest {

    private fun stats(vararg rows: Pair<MessageQueue, Pair<Long, Long>>): ConsumeStats =
        ConsumeStats().apply {
            offsetTable = rows.associate { (mq, offsets) ->
                mq to OffsetWrapper().apply {
                    consumerOffset = offsets.first
                    brokerOffset = offsets.second
                }
            }
        }

    private fun mq(broker: String, queueId: Int, topic: String = "ChillbearNormal") =
        MessageQueue(topic, broker, queueId)

    private fun brokerList(group: String, vararg entries: ConsumeStats): ConsumeStatsList =
        ConsumeStatsList().apply { consumeStatsList = listOf(mapOf(group to entries.toList())) }

    /** 组级失败时把协议异常包成 IllegalStateException —— 复刻 ioCall 的形态。 */
    private fun wrapped(message: String, code: Int): Throwable =
        IllegalStateException(message, MQClientException(code, message))

    @Test
    fun `group level success never touches the brokers`() = runBlocking {
        var brokerCalls = 0
        val outcome = resolveGroupProgress(
            group = "G",
            fetchGroupLevel = { stats(mq("broker-a", 0) to (3L to 10L)) },
            fetchBrokerLevel = { brokerCalls++; null },
            brokerAddrs = { listOf("broker-a") },
        )
        assertEquals(RocketMqProgressState.OK, outcome.state)
        assertEquals(RocketMqProgressSource.GROUP, outcome.source)
        assertEquals(7L, outcome.rows.single().lag)
        assertEquals(0, brokerCalls, "组级成功时不能多打一轮 broker（大集群代价很大）")
    }

    @Test
    fun `code 17 with offsets on the broker recovers through the fallback`() = runBlocking {
        // 原型：CG_PROGRESS_PROBE —— 组级 17，broker 上有位点。
        val outcome = resolveGroupProgress(
            group = "CG_PROGRESS_PROBE",
            fetchGroupLevel = { throw wrapped("No topic route info: %RETRY%CG_PROGRESS_PROBE", 17) },
            fetchBrokerLevel = { addr ->
                if (addr == "broker-b") {
                    brokerList("CG_PROGRESS_PROBE", stats(mq("broker-b", 0) to (1L to 1L)))
                } else {
                    brokerList("CG_PROGRESS_PROBE", stats(mq(addr, 0) to (0L to 0L)))
                }
            },
            brokerAddrs = { listOf("broker-a", "broker-b") },
        )
        assertEquals(RocketMqProgressState.OK, outcome.state)
        assertEquals(RocketMqProgressSource.BROKER_FALLBACK, outcome.source)
        assertNull(outcome.error, "兜底成功后不该再留着失败原因")
        assertEquals(2, outcome.rows.size)
    }

    @Test
    fun `code 17 with no offsets anywhere keeps the degraded state`() = runBlocking {
        // 原型：CG_Normal —— 真没有位点，不能因为「兜底跑过了」就报 OK。
        val outcome = resolveGroupProgress(
            group = "CG_Normal",
            fetchGroupLevel = { throw wrapped("No topic route info: %RETRY%CG_Normal", 17) },
            fetchBrokerLevel = { ConsumeStatsList() },
            brokerAddrs = { listOf("broker-a", "broker-b", "broker-c") },
        )
        assertEquals(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE, outcome.state)
        assertEquals(RocketMqProgressSource.GROUP, outcome.source)
        assertTrue(outcome.rows.isEmpty())
    }

    @Test
    fun `code 206 also triggers the fallback`() = runBlocking {
        val outcome = resolveGroupProgress(
            group = "G",
            fetchGroupLevel = { throw wrapped("offset table is empty, maybe the consumer not online", 206) },
            fetchBrokerLevel = { brokerList("G", stats(mq("broker-a", 1) to (5L to 8L))) },
            brokerAddrs = { listOf("broker-a") },
        )
        assertEquals(RocketMqProgressState.OK, outcome.state)
        assertEquals(RocketMqProgressSource.BROKER_FALLBACK, outcome.source)
        assertEquals(3L, outcome.rows.single().lag)
    }

    @Test
    fun `permission failure does not trigger the fallback`() = runBlocking {
        // 权限不足时 broker 直读同样会被拒 —— 再查一轮只是把一次失败放大成 N 次。
        var brokerCalls = 0
        val outcome = resolveGroupProgress(
            group = "G",
            fetchGroupLevel = { throw MQBrokerException(1, "No permission to access group G") },
            fetchBrokerLevel = { brokerCalls++; null },
            brokerAddrs = { listOf("broker-a", "broker-b") },
        )
        assertEquals(RocketMqProgressState.NO_PERMISSION, outcome.state)
        assertEquals(0, brokerCalls)
    }

    @Test
    fun `timeout does not trigger the fallback and is reported clearly`() = runBlocking {
        var brokerCalls = 0
        val outcome = resolveGroupProgress(
            group = "G",
            // 组级调用一直不返回：策略里的 withTimeout(5s) 必须兜住。
            fetchGroupLevel = { delay(Long.MAX_VALUE); ConsumeStats() },
            fetchBrokerLevel = { brokerCalls++; null },
            brokerAddrs = { listOf("broker-a") },
        )
        assertEquals(RocketMqProgressState.FAILED, outcome.state)
        assertTrue(
            outcome.error!!.contains("超时"),
            "超时要单独说清楚，不能混成「查询失败」：${outcome.error}",
        )
        assertEquals(0, brokerCalls)
    }

    @Test
    fun `unknown failure keeps the raw detail`() = runBlocking {
        val outcome = resolveGroupProgress(
            group = "G",
            fetchGroupLevel = { throw RemotingTimeoutException("wait response on the channel timeout") },
            fetchBrokerLevel = { null },
            brokerAddrs = { listOf("broker-a") },
        )
        assertEquals(RocketMqProgressState.FAILED, outcome.state)
        assertTrue(outcome.error!!.contains("timeout"), "实测：${outcome.error}")
    }

    @Test
    fun `a broker that fails only loses its own rows`() = runBlocking {
        val outcome = resolveGroupProgress(
            group = "G",
            fetchGroupLevel = { throw wrapped("No topic route info", 17) },
            fetchBrokerLevel = { addr ->
                if (addr == "broker-a") throw IllegalStateException("connect refused")
                brokerList("G", stats(mq(addr, 0) to (0L to 4L)))
            },
            brokerAddrs = { listOf("broker-a", "broker-b") },
        )
        assertEquals(RocketMqProgressState.OK, outcome.state)
        assertEquals(listOf("broker-b"), outcome.rows.map { it.broker })
    }

    @Test
    fun `registry lookup failure degrades instead of throwing`() = runBlocking {
        val outcome = resolveGroupProgress(
            group = "G",
            fetchGroupLevel = { throw wrapped("No topic route info", 17) },
            fetchBrokerLevel = { null },
            brokerAddrs = { throw IllegalStateException("cluster info unavailable") },
        )
        assertEquals(RocketMqProgressState.RETRY_TOPIC_NO_ROUTE, outcome.state)
        assertTrue(outcome.rows.isEmpty())
    }

    @Test
    fun `cancellation propagates instead of being swallowed`() = runBlocking {
        // 关掉对话框要能真的停下来：CancellationException 不能被当成「查询失败」。
        var caught: Throwable? = null
        try {
            resolveGroupProgress(
                group = "G",
                fetchGroupLevel = { throw kotlinx.coroutines.CancellationException("dialog closed") },
                fetchBrokerLevel = { null },
                brokerAddrs = { listOf("broker-a") },
            )
        } catch (e: Throwable) {
            caught = e
        }
        assertTrue(caught is kotlinx.coroutines.CancellationException, "取消必须原样上抛，实测：$caught")
    }
}
