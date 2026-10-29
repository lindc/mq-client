package com.mqclient.ibmmq.ui

import com.mqclient.uicommon.i18n.LanguageResources
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 七类简单对象列表页的 i18n 键守卫。
 *
 * [SimpleMqObjectListPanel] 的键是**运行期拼出来的**（`"${spec.i18nPrefix}_refresh"`），
 * `scripts/check_i18n_refs.py` 只扫字面量 `stringResource("…")`，扫不到这一族 ——
 * 2026-10「模块私有键统一加 `ibm_mq_` 前缀」那次重构就这样漏了这 7 个页面：键集中改了名，
 * 这里的 spec 前缀没跟着加，界面上搜索框 / 刷新按钮直接显示 `topic_refresh` 这种裸 key。
 *
 * 所以守卫放在这里而不是扫描脚本里：按**与面板完全相同的派生规则**逐个查两种语言的表，
 * 缺一个就红。新增简单对象类型时把 spec 加进 [specs] 即可。
 */
class SimpleMqObjectI18nKeysTest {

    /** 被 GUI 使用的全部简单对象 spec（与 `IbmMqUI.contentFor` 的门面一一对应）。 */
    private val specs =
        listOf(AuthInfoSpec, CommInfoSpec, NamelistSpec, ProcessSpec, ServiceSpec, SubscriptionSpec, TopicSpec)

    /** [SimpleMqObjectListPanel] 内实际拼接的后缀（含卡片 tooltip 用的 `_action_properties`）。 */
    private val suffixes =
        listOf(
            "_search_placeholder",
            "_refresh",
            "_loading",
            "_load_failed",
            "_scan_failed",
            "_action_properties",
        )

    private val languages = listOf(LanguageResources.LANGUAGE_ZH_CN, LanguageResources.LANGUAGE_EN_US)

    @Test
    fun `spec prefixes carry the module key prefix`() {
        val offenders = specs.map { it.i18nPrefix }.filter { !it.startsWith("ibm_mq_") }
        assertTrue(
            offenders.isEmpty(),
            "i18nPrefix 必须是完整键前缀（含模块前缀 ibm_mq_），否则 stringResource 查不到：$offenders",
        )
    }

    @Test
    fun `every simple object key resolves in both languages`() = runBlocking {
        val table = IbmMqUI().i18nTables.first { it.base == "ibm_mq" }
        val expected = specs.flatMap { spec -> suffixes.map { "${spec.i18nPrefix}$it" } }

        for (language in languages) {
            val keys = parseKeys(table.reader(language))
            // 防假绿：表读不出来时 keys 是空集，下面的「全都存在」会失效。
            assertTrue(keys.isNotEmpty(), "$language 模块表一个键都没读到，守卫会空转")
            val missing = expected.filter { it !in keys }
            assertTrue(
                missing.isEmpty(),
                "$language 缺以下键（界面会直接显示 key 本身）：$missing",
            )
        }
    }

    /** 模块表是扁平 `{"key": "value"}`，取键集合即可 —— 值的非空由表本身与其它用例保证。 */
    private fun parseKeys(text: String?): Set<String> =
        text?.let { Json.parseToJsonElement(it).jsonObject.keys } ?: emptySet()
}
