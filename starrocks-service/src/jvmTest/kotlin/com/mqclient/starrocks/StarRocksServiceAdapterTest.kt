package com.mqclient.starrocks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * service 侧 SPI 的**装配合约冒烟测试**：验证身份 / 分类常量与「注册后宿主会
 * 调用的三个入口不会抛」，不依赖真实数据库、不触网、不验证任何协议逻辑。
 *
 * 注册到 `META-INF/services` 之后，`description`（渲染卡片）、`initialize`
 * （启动时）、`dispose`（退出时）都会被宿主直接调用，不能留 TODO()。
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
    fun `registered entry points are safe for the host to call`() {
        // description 是 i18n 键（宿主 toMiddlewareType 查表翻译），不能是裸英文或 TODO。
        assertEquals(
            StarRocksConstants.I18N_KEY_PREFIX + "description",
            adapter.description,
        )
        // 启动 / 退出时宿主直接调用，不能抛。
        adapter.dispose()
        assertTrue(adapter.navigationItems(dummyConnection()).isNotEmpty())
    }

    @Test
    fun `navigation contributes the five placeholder menu entries`() {
        val items = adapter.navigationItems(dummyConnection())
        assertEquals(listOf("overview", "catalog", "console", "topology", "queries"), items.map { it.id })
        // titleKey 走公共表 olap.starrocks.menu.* 前缀；iconId 必须是宿主 NavIcons
        // 已登记的 id，否则树图标回落 Circle（空心圆）。
        assertEquals(
            listOf(
                "olap.starrocks.menu.overview",
                "olap.starrocks.menu.catalog",
                "olap.starrocks.menu.console",
                "olap.starrocks.menu.topology",
                "olap.starrocks.menu.queries",
            ),
            items.map { it.titleKey },
        )
        assertEquals(
            listOf("clusterRelations", "namelists", "command", "topology", "processDefinitions"),
            items.map { it.iconId },
        )
        // badge 仍是骨架占位。
        assertTrue(adapter.badgeCounts("test-connection") == kotlinx.coroutines.flow.emptyFlow<Map<String, Int>>())
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
