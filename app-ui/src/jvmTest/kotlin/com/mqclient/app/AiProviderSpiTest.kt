package com.mqclient.app

import com.mqclient.core.ModuleRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AI provider 的 SPI 装配契约（**防止「看着没人引用」就删掉活代码**）。
 *
 * 背景：`OpenAiConnectionForm` / `AnthropicConnectionForm` 在全仓只有注释里出现，
 * `grep` 找不到任何直接调用 —— 因为调用发生在**运行时**：
 * `META-INF/services/com.mqclient.core.ConnectionFormUi` → `ModuleRegistry.forms`
 * → `NewConnectionDialog` 遍历装配。曾经据此把它们判成死代码，差点删掉「新建连接 →
 * AI」整条路径（删了只会看到 `new_connection_form_todo` 占位）。
 *
 * 所以这里按**装配结果**断言，而不是按源码引用：
 *  - 每个 AI provider 都有表单注册（否则新建连接里点进去是空面板）；
 *  - 每个 AI provider 都有品牌图标注册（否则设置页回落到 OpenAI 的 logo，
 *    Anthropic 连接会显示成 OpenAI）；
 *  - 表单声明的 serviceId 必须与 provider id 一致（宿主按它配对）。
 */
class AiProviderSpiTest {

    /** 两个内置 AI provider（与 `AiProviders` / `AnthropicProviders` 的 id 同值）。 */
    private val providerIds = listOf("openai-compatible", "anthropic")

    @Test
    fun `every ai provider has a connection form registered`() {
        val formServiceIds = ModuleRegistry.forms.map { it.serviceId }

        for (id in providerIds) {
            assertTrue(
                id in formServiceIds,
                "provider「$id」没有 ConnectionFormUi 注册 —— 新建连接里点进去会是空面板。" +
                    "已注册：$formServiceIds",
            )
        }
    }

    @Test
    fun `every ai provider has a brand icon registered`() {
        val iconServiceIds = ModuleRegistry.icons.map { it.serviceId }

        for (id in providerIds) {
            assertTrue(
                id in iconServiceIds,
                "provider「$id」没有 ModuleIcon 注册 —— 设置页会回落到 OpenAI 的 logo。" +
                    "已注册：$iconServiceIds",
            )
        }
    }

    @Test
    fun `ai providers appear in their own category so the wizard shows them`() {
        // 新建连接的第一步按 category 分组；AI 类目存在才有点进去的可能。
        val grouped = ModuleRegistry.groupByCategory(ModuleRegistry.services)
        val aiIds = grouped["AI"].orEmpty().map { it.id }

        for (id in providerIds) {
            assertTrue(id in aiIds, "provider「$id」不在 AI 类目里：${grouped.keys}")
        }
    }

    @Test
    fun `no duplicate form registration for the same provider`() {
        // 两个 provider 各注册一个；同一 serviceId 注册两次会让「点卡片」行为不确定。
        val duplicates = ModuleRegistry.forms.groupBy { it.serviceId }.filterValues { it.size > 1 }

        assertEquals(emptyMap(), duplicates, "同一 provider 注册了多个表单")
    }
}
