package com.mqclient.ai

import com.mqclient.core.AiConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 注册表的契约：内置三件套可解析、未知 id 返回 null、注册时 id 不一致直接拒绝。 */
class AiAdapterRegistryTest {

    @Test
    fun `builtin providers and adapters resolve by id`() {
        val ids = AiAdapterRegistry.providers().map { it.id }
        assertEquals(
            listOf("openai-compatible", "claude", "gemini"),
            ids.sortedBy { listOf("openai-compatible", "claude", "gemini").indexOf(it) },
        )

        assertEquals("openai-compatible", AiAdapterRegistry.adapter("openai-compatible")?.providerId)
        assertEquals("claude", AiAdapterRegistry.adapter("claude")?.providerId)
        assertEquals("gemini", AiAdapterRegistry.adapter("gemini")?.providerId)
        assertNotNull(AiAdapterRegistry.provider("claude"))
        assertNull(AiAdapterRegistry.adapter("nope"))
        assertNull(AiAdapterRegistry.provider("nope"))
    }

    @Test
    fun `adapterFor resolves through the connection's provider id`() {
        val connection = AiConnection(id = "c1", name = "x", providerId = "claude")
        assertEquals("claude", AiAdapterRegistry.adapterFor(connection)?.providerId)
    }

    @Test
    fun `register rejects mismatched provider and adapter ids`() {
        val provider = AiProvider(id = "custom", name = "Custom")
        val adapter = object : AiAdapter {
            override val providerId = "other"
            override suspend fun chat(connection: AiConnection, messages: List<AiChatMessage>) = AiResponse("", "")
            override fun chatStream(connection: AiConnection, messages: List<AiChatMessage>): Flow<AiStreamChunk> =
                flowOf()
            override suspend fun testConnection(connection: AiConnection) = false
        }
        assertFailsWith<IllegalArgumentException> { AiAdapterRegistry.register(provider, adapter) }
    }
}
