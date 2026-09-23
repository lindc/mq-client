package com.mqclient.app

import com.mqclient.core.ModuleRegistry
import com.mqclient.uicommon.i18n.LanguageResources
import com.mqclient.uicommon.i18n.stringResource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 模块私有 i18n 表经 SPI 合并进运行时全局表的端到端验证（见
 * `AdapterUI.i18nTables` / `LanguageResources.load`）。
 *
 * 测试类路径上装着全部业务模块（app-ui 依赖它们），所以 `ModuleRegistry.i18nTables`
 * 至少能发现 ibm-mq 的 `ibm_mq` 表 —— 断言它的键经 `stringResource` 能解析出译文，
 * 而不是回落到 key 本身（回归过一次：键出现在界面上，就是模块表没读进来）。
 */
class ModuleI18nMergeTest {

    @AfterTest
    fun tearDown() {
        // 恢复公共表，别把其他用例的期望值污染掉（本模块没有别的 i18n 用例，防御性收尾）。
        runBlocking { LanguageResources.load(null) }
    }

    @Test
    fun `module tables merge through SPI and keys resolve`() = runBlocking {
        assertTrue(
            ModuleRegistry.i18nTables.any { it.base == "ibm_mq" },
            "classpath 上应能发现 ibm-mq 的私有字符串表",
        )

        LanguageResources.load("zh-CN", ModuleRegistry.i18nTables)
        assertEquals("队列", stringResource("nav_queues"))
        assertEquals("拓扑图", stringResource("nav_topology"))
        // 公共表照常工作（模块键不覆盖公共键）。
        assertEquals("文件", stringResource("menu_file"))

        LanguageResources.load("en-US", ModuleRegistry.i18nTables)
        assertEquals("Queues", stringResource("nav_queues"))
        assertEquals("Authentication Information", stringResource("nav_auth_info"))
    }

    @Test
    fun `missing module table degrades to the key itself`() = runBlocking {
        LanguageResources.load("zh-CN", ModuleRegistry.i18nTables)
        assertEquals("nav_not_a_real_key", stringResource("nav_not_a_real_key"))
    }
}
