package com.mqclient.app

import com.mqclient.core.ModuleRegistry
import com.mqclient.uicommon.i18n.LanguageResources
import com.mqclient.uicommon.i18n.stringResource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 模块私有 i18n 表经 SPI 合并进运行时全局表的端到端验证（见
 * `AdapterUI.i18nTables` / `LanguageResources.load`）。
 *
 * 测试类路径上装着全部业务模块（app-ui 依赖它们），所以 `ModuleRegistry.i18nTables`
 * 至少能发现 ibm-mq 的 `ibm_mq` 表 —— 断言它的键经 `stringResource` 能解析出译文，
 * 而不是回落到 key 本身（回归过一次：键出现在界面上，就是模块表没读进来）。
 *
 * 另外承担**键名约定的守卫**（见 `AdapterUI.i18nTables` 的键名约定）：模块表是全局
 * 合并的，重名会静默互相覆盖，而合并顺序取 SPI 的 classpath 顺序，换个构建就可能翻转 ——
 * 所以模块间重名、模块覆盖公共表这两类都得在测试里拦住。
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
        assertEquals("队列", stringResource("ibm_mq_nav_queues"))
        assertEquals("拓扑图", stringResource("ibm_mq_nav_topology"))
        // 公共表照常工作（模块键不覆盖公共键）。
        assertEquals("文件", stringResource("menu_file"))

        LanguageResources.load("en-US", ModuleRegistry.i18nTables)
        assertEquals("Queues", stringResource("ibm_mq_nav_queues"))
        assertEquals("Authentication Information", stringResource("ibm_mq_nav_auth_info"))
    }

    @Test
    fun `missing module table degrades to the key itself`() = runBlocking {
        LanguageResources.load("zh-CN", ModuleRegistry.i18nTables)
        assertEquals("nav_not_a_real_key", stringResource("nav_not_a_real_key"))
    }

    /**
     * 模块表之间**不得有重名键**：它们被合并进同一张全局表，重名时后加载的静默覆盖先
     * 加载的，而「后加载」取决于 SPI 的 classpath 顺序 —— 换个构建 / 机器就可能翻转，
     * 表现为某个模块的文案变成另一个模块的，而且不报错。
     */
    @Test
    fun `module tables do not collide with each other`() = runBlocking {
        val byBase = ModuleRegistry.i18nTables.associate { table ->
            table.base to parseKeys(table.reader(LanguageResources.LANGUAGE_ZH_CN))
        }
        val bases = byBase.keys.sorted()
        // 防空转：表读不出来时每个集合都是空集，交集恒为空 —— 守卫会“假绿”。
        assertTrue(
            bases.size >= 2 && byBase.values.all { it.isNotEmpty() },
            "有模块表读不出键，守卫会空转：${byBase.mapValues { it.value.size }}",
        )
        val collisions = buildList {
            for (i in bases.indices) {
                for (j in i + 1 until bases.size) {
                    val shared = byBase.getValue(bases[i]) intersect byBase.getValue(bases[j])
                    if (shared.isNotEmpty()) {
                        add("${bases[i]} × ${bases[j]}: ${shared.sorted().joinToString()}")
                    }
                }
            }
        }
        assertTrue(
            collisions.isEmpty(),
            "模块 i18n 表键重名（会静默互相覆盖）：\n" + collisions.joinToString("\n"),
        )
    }

    /**
     * 模块表**不得覆盖公共表**：宿主与跨模块词汇（`nav_*` / `common_*` / `menu_*` …）
     * 归 ui-common 所有，模块想要不同措辞应使用自己前缀的键。
     *
     * 检测手法：只加载公共表（不传模块表），再逐个探测模块键 —— `stringResource`
     * 查不到时原样返回 key，所以「返回值不等于 key 本身」即说明这个键住在公共表里。
     * 宿主读不到 ui-common 的资源（Compose 资源按库包名隔离），只能这样旁证。
     */
    @Test
    fun `module tables do not shadow the common table`() = runBlocking {
        val moduleKeys = ModuleRegistry.i18nTables
            .flatMap { parseKeys(it.reader(LanguageResources.LANGUAGE_ZH_CN)) }
            .toSet()
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN) // 只有公共表
        assertTrue(moduleKeys.isNotEmpty(), "模块键一个都没读到，守卫会空转")
        val shadowed = moduleKeys.filter { stringResource(it) != it }.sorted()
        assertTrue(shadowed.isEmpty(), "模块键与公共表重名：$shadowed")
    }

    /** 解析模块表的原始 JSON，取键集合（表都是扁平 `{"key": "value"}`）。 */
    private fun parseKeys(text: String?): Set<String> =
        text?.let { Json.parseToJsonElement(it).jsonObject.keys } ?: emptySet()
}
