package com.mqclient.mq.base

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `mq-base` 是 MQ 系模块的共同父类：它唯一的行为是**替子类钉住类目**。
 *
 * 这里守的是两条约定：
 *  1. `MQServiceAdapter.category` 与 [MQConstants.CATEGORY] 同源（不是各写一份字面量）；
 *  2. 默认值常量是各模块 Config 的**对齐基准** —— 改动会影响新建连接的默认输入框，
 *     所以写成显式断言，改的时候至少得有人看见。
 */
class MQServiceAdapterTest {

    /** 最小子类：只实现 SPI 要求的描述字段，验证基类把 category 填好。 */
    private class FakeMqAdapter(
        override val id: String = "fake-mq",
        override val name: String = "Fake MQ",
        override val description: String = "test only",
        override val protocol: String = "FAKE/1.0",
    ) : MQServiceAdapter()

    @Test
    fun `base class pins the MQ category for every subclass`() {
        assertEquals(MQConstants.CATEGORY, FakeMqAdapter().category)
        assertEquals("MQ", FakeMqAdapter().category, "新建连接对话框的 tab 归组依赖这个字面值")
    }

    @Test
    fun `subclass description fields pass through untouched`() {
        val adapter = FakeMqAdapter(id = "ibm-mq-test", name = "IBM MQ", protocol = "MQSC")

        assertEquals("ibm-mq-test", adapter.id)
        assertEquals("IBM MQ", adapter.name)
        assertEquals("MQSC", adapter.protocol)
        assertEquals("test only", adapter.description)
    }

    @Test
    fun `category is a single source of truth`() {
        // 两个子类实例的类目必须完全一致（防止有人覆盖成字面量 "mq" / "MQ "）。
        assertEquals(FakeMqAdapter(id = "a").category, FakeMqAdapter(id = "b").category)
    }

    @Test
    fun `shared default constants stay aligned with module configs`() {
        // 与 ibm-mq / kafka / rabbitmq / rocketmq 的 Config 默认值同值；改这里等于改
        // 「新建连接」表单的默认输入，必须是刻意决定。
        assertEquals(10, MQConstants.DEFAULT_MESSAGE_SCAN_INTERVAL_SECONDS)
        assertEquals(60, MQConstants.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES)
        assertEquals(30, MQConstants.DEFAULT_CONNECTION_TIMEOUT_SECONDS)
    }
}
