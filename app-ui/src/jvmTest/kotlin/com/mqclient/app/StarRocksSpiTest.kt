package com.mqclient.app

import com.mqclient.core.ModuleRegistry
import com.mqclient.starrocks.ui.StarRocksAdapterUI
import com.mqclient.uicommon.i18n.LanguageResources
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * StarRocks 插件的 **SPI 装配合约守卫**（与 AiProviderSpiTest 同款：按运行时
 * 装配结果断言，而不是按源码引用 —— SPI 类在仓库里「看起来没人调用」）。
 *
 * 覆盖：
 *  - OLAP 分类由 ServiceAdapter.category 动态合并出现（宿主零改动）；
 *  - StarRocks 卡片元数据（name / description）安全可读、描述经 i18n 解析；
 *  - AdapterUI 已注册且与 service 按 id / category 配对；
 *  - **现有 4 个 MQ 协议仍在 "MQ" 分类**（回归守卫：新分类不挤掉旧分类）。
 */
class StarRocksSpiTest {

    private val mqIds = listOf("ibm-mq", "kafka", "rabbitmq", "rocketmq")

    @AfterTest
    fun tearDown() {
        runBlocking { LanguageResources.load(null) }
    }

    @Test
    fun `OLAP category appears from SPI and contains starrocks`() {
        val grouped = ModuleRegistry.groupByCategory(ModuleRegistry.services)
        assertTrue("OLAP" in grouped.keys, "OLAP 分类未出现：${grouped.keys}")
        assertTrue(
            grouped["OLAP"].orEmpty().any { it.id == "starrocks" },
            "StarRocks 服务未注册到 OLAP 分类",
        )
    }

    @Test
    fun `existing four MQ protocols stay in MQ category`() {
        val grouped = ModuleRegistry.groupByCategory(ModuleRegistry.services)
        val mqIdsInGroup = grouped["MQ"].orEmpty().map { it.id }
        for (id in mqIds) {
            assertTrue(id in mqIdsInGroup, "$id 从 MQ 分类消失了：${grouped.keys}")
        }
    }

    @Test
    fun `starrocks card metadata is safe and localized`() {
        val adapter = ModuleRegistry.services.first { it.id == "starrocks" }
        assertEquals("StarRocks", adapter.name)
        assertEquals("OLAP", adapter.category)
        // description 不能再是 TODO()：宿主渲染卡片时会直接读它。
        assertTrue(adapter.description.isNotBlank())
        runBlocking { LanguageResources.load(LanguageResources.LANGUAGE_EN_US) }
        assertEquals(
            "StarRocks connection and analytics",
            stringResource(adapter.description),
            "描述必须是可解析的 i18n 键，不能把裸 key 显示给用户",
        )
    }

    @Test
    fun `AdapterUI is registered and paired with its ServiceAdapter`() {
        val ui = ModuleRegistry.uis.firstOrNull { it.serviceId == "starrocks" }
        assertTrue(ui != null, "StarRocksAdapterUI 未被 ServiceLoader 发现")
        val service = ModuleRegistry.services.first { it.id == "starrocks" }
        assertEquals(service.id, ui.serviceId)
        assertEquals(
            service.category,
            StarRocksAdapterUI().category,
            "UI 与 service 的分类必须一致（引用同一常量）",
        )
    }

    @Test
    fun `no unknown UI service id reported for starrocks`() {
        val known = ModuleRegistry.services.mapTo(mutableSetOf()) { it.id }
        assertTrue("starrocks" in known)
        assertTrue(
            ModuleRegistry.uis.none { it.serviceId == "starrocks" && it.serviceId !in known },
        )
    }
}
