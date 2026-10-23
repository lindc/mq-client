package com.mqclient.rocketmq.ui

import com.mqclient.rocketmq.RocketMqBrokerSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

/** [RocketMqBrokerPropertiesDialog] 的数据整理逻辑：实例筛选 / 排序与地址拆分。 */
class RocketMqBrokerPropertiesDialogTest {

    private fun broker(
        name: String,
        id: Long,
        address: String,
        role: String,
        cluster: String = "cluster-a",
    ) = RocketMqBrokerSnapshot(
        cluster = cluster,
        brokerName = name,
        brokerId = id,
        address = address,
        role = role,
    )

    @Test
    fun `brokerInstances 只保留同名 Broker 并按 ID 升序`() {
        val brokers = listOf(
            broker("broker-a", 1, "10.0.0.2:10911", "Slave"),
            broker("broker-b", 0, "10.0.0.3:10911", "Master"),
            broker("broker-a", 0, "10.0.0.1:10911", "Master"),
        )

        val instances = brokerInstances(brokers, "broker-a")

        assertEquals(listOf(0L, 1L), instances.map { it.brokerId })
        assertEquals(listOf("Master", "Slave"), instances.map { it.role })
    }

    @Test
    fun `brokerInstances 找不到同名 Broker 时返回空列表`() {
        val brokers = listOf(broker("broker-a", 0, "10.0.0.1:10911", "Master"))

        assertEquals(emptyList(), brokerInstances(brokers, "broker-missing"))
    }

    @Test
    fun `brokerAddressParts 拆分 IPv4 主机与端口`() {
        assertEquals("10.0.0.1" to "10911", brokerAddressParts("10.0.0.1:10911"))
    }

    @Test
    fun `brokerAddressParts 拆分 IPv6 时保留方括号`() {
        assertEquals("[::1]" to "10911", brokerAddressParts("[::1]:10911"))
    }

    @Test
    fun `brokerAddressParts 没有端口时端口回落为短横线`() {
        assertEquals("broker-a" to "-", brokerAddressParts("broker-a"))
        assertEquals("" to "-", brokerAddressParts(""))
        assertEquals("10.0.0.1:" to "-", brokerAddressParts("10.0.0.1:"))
    }
}
