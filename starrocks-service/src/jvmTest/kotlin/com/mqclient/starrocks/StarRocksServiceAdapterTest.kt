package com.mqclient.starrocks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * service 侧 SPI 空骨架的**契约冒烟测试**：只验证身份 / 分类常量与「占位方法
 * 确实抛 NotImplemented」，不依赖真实数据库、不触网、不验证任何协议逻辑。
 */
class StarRocksServiceAdapterTest {

    private val adapter = StarRocksServiceAdapter()

    @Test
    fun `skeleton exposes protocol identity and olap category`() {
        assertEquals("starrocks", adapter.id)
        assertEquals("StarRocks", adapter.name)
        assertEquals("StarRocks", adapter.protocol)
        // 分类来自 olap-base 的单一来源，禁止在本模块硬编码。
        assertEquals(com.mqclient.olap.base.OlapCategory.CATEGORY, adapter.category)
    }

    @Test
    fun `protocol lifecycle methods remain unimplemented placeholders`() {
        assertFailsWith<NotImplementedError> { adapter.description }
        assertFailsWith<NotImplementedError> { adapter.dispose() }
    }

    @Test
    fun `navigation and badge contributions are empty in skeleton`() {
        assertTrue(adapter.navigationItems(dummyConnection()).isEmpty())
    }

    private fun dummyConnection(): com.mqclient.core.ConnectionInfo =
        com.mqclient.core.ConnectionInfo(
            id = "test-connection",
            name = "test",
            serviceId = "starrocks",
            mqName = "starrocks",
            host = "localhost",
            port = 9030,
        )
}
