package com.mqclient.rocketmq

import com.mqclient.core.ServiceException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

/**
 * [RocketMqMessageClients] 的关闭语义：**断开之后不再能凭空造出句柄**。
 *
 * 这里不碰网络 —— TLS 关闭时构造与 shutdown 都是纯内存操作，正好用来验证关闭标志。
 * 真实故障形态是：`clients()` 拿到对象与 `shutdown()` 并发，创建路径在字段被清空之后
 * 又 start 了一个客户端，赋给已报废的持有者，从此没人关（Netty + 心跳线程泄漏，
 * TLS 下还会拿已删除的 PEM 去握手）。
 */
class RocketMqMessageClientsLifecycleTest {

    @Test
    fun `accessors refuse to create handles after shutdown`() {
        runBlocking {
            val clients = RocketMqMessageClients(RocketMqConfig(namesrvAddr = "127.0.0.1:9876"))

            clients.shutdown()

            assertFailsWith<ServiceException>("共享 PullConsumer") { clients.pullConsumer() }
            assertFailsWith<ServiceException>("共享 Producer") { clients.producer() }
            assertFailsWith<ServiceException>("临时 PullConsumer") { clients.tempPullConsumer() }
        }
    }

    @Test
    fun `shutdown is idempotent and safe with no handles`() {
        runBlocking {
            val clients = RocketMqMessageClients(RocketMqConfig(namesrvAddr = "127.0.0.1:9876"))

            clients.shutdown()
            clients.shutdown()

            assertFailsWith<ServiceException> { clients.pullConsumer() }
        }
    }
}
