package com.mqclient.ai

import com.mqclient.core.AiConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 注册表的契约：**自己不知道任何 provider** —— 空表起步、登记后可解析、
 * 未知 id 返回 null、provider 与 adapter 的 id 不一致直接拒绝。
 *
 * 具体 provider 的登记时机是各模块的 `ServiceAdapter.initialize`（见
 * openai-service / anthropic-service 的 initialize），所以这里不假设内置项存在。
 */
class AiAdapterRegistryTest {

    private fun adapter(id: String) = object : AiAdapter {
        override val providerId = id
        override suspend fun chat(connection: AiConnection, messages: List<AiChatMessage>) =
            AiResponse("", "")

        override fun chatStream(connection: AiConnection, messages: List<AiChatMessage>): Flow<AiStreamChunk> =
            flowOf()

        override suspend fun testConnection(connection: AiConnection) = false
    }

    @Test
    fun `registry does not invent providers - unknown ids resolve to null`() {
        // ai-core 不认识任何 provider：openai-compatible 的预置与登记都在
        // openai-service，本模块的测试 classpath 上没有它，所以查不到就是查不到。
        assertNull(AiAdapterRegistry.adapter("openai-compatible"))
        assertNull(AiAdapterRegistry.adapter("nope"))
        assertNull(AiAdapterRegistry.provider("nope"))
    }

    @Test
    fun `registered provider and adapter resolve by id - adapterFor follows connection`() {
        val provider = AiProvider(id = "test-provider", name = "Test")
        AiAdapterRegistry.register(provider, adapter("test-provider"))

        assertEquals("test-provider", AiAdapterRegistry.adapter("test-provider")?.providerId)
        assertEquals("test-provider", AiAdapterRegistry.provider("test-provider")?.id)

        val connection = AiConnection(id = "c1", name = "x", providerId = "test-provider")
        assertEquals("test-provider", AiAdapterRegistry.adapterFor(connection)?.providerId)
    }

    @Test
    fun `register rejects mismatched provider and adapter ids`() {
        val provider = AiProvider(id = "custom", name = "Custom")
        assertFailsWith<IllegalArgumentException> {
            AiAdapterRegistry.register(provider, adapter("other"))
        }
    }
}
