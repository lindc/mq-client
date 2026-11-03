package com.mqclient.ai.anthropic

import com.mqclient.ai.AiChatMessage
import com.mqclient.ai.AiException
import com.mqclient.ai.AiRole
import com.mqclient.ai.adapter.AiHttp
import com.mqclient.core.ConfigStore
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import com.mqclient.testing.assumeNotNull

/**
 * Anthropic 适配器真机验证（需要 ~/.mqclient/config.toml 里有 providerId="anthropic"
 * 且 token 可用的连接）：走 core 的解密加载链路取连接，依次验证 testConnection /
 * 非流式 / 流式三条路径，逐段打印诊断信息。
 *
 * 没有 anthropic 连接时打印 SKIP 并通过 —— 不让环境问题污染离线 CI。
 * 有连接但 API 失败时测试失败并带上 AiException 的状态码与服务端说明 ——
 * 那正是要暴露的验证结论。
 */
class AnthropicAdapterLiveTest {

    @Test
    fun liveChatAndStream() = runBlocking {
        // 无环境 = SKIP（assumption），不是 PASSED。
        val connection = assumeNotNull(
            "LIVE SKIP: config.toml 里没有启用的 anthropic 连接",
            ConfigStore.loadConfig().ai.connections.firstOrNull { it.providerId == "anthropic" && it.enabled },
        )
        println(
            "LIVE connect: name=${connection.name} baseUrl=${connection.baseUrl} " +
                "model=${connection.model} tokenLen=${connection.token.length}",
        )
        val adapter = AnthropicAdapter(AiHttp.client)

        // 1. testConnection（Models API）——仅诊断信息：第三方兼容服务可能不实现它。
        val ok = adapter.testConnection(connection)
        println("LIVE testConnection=$ok（false 不阻断，继续验证对话路径）")

        // 2. 非流式对话。
        val response = try {
            adapter.chat(connection, listOf(AiChatMessage(AiRole.USER, "用一句话回答：1+1等于几？")))
        } catch (e: AiException) {
            fail("LIVE chat FAIL: HTTP ${e.statusCode} — ${e.message}")
        }
        println(
            "LIVE chat OK: content=${response.content.take(120)} " +
                "model=${response.model} tokens=${response.tokensUsed} finish=${response.finishReason}",
        )
        assertTrue(response.content.isNotBlank(), "非流式回复为空")
        assertTrue(response.content.contains("2"), "非流式回复内容与提问不符：${response.content}")

        // 3. 流式对话。
        val text = StringBuilder()
        var finishReason: String? = null
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var totalTokens: Int? = null
        try {
            adapter.chatStream(connection, listOf(AiChatMessage(AiRole.USER, "从1数到5，用顿号分隔")))
                .collect { chunk ->
                    text.append(chunk.delta)
                    if (chunk.finishReason != null) finishReason = chunk.finishReason
                    if (chunk.promptTokens != null) promptTokens = chunk.promptTokens
                    if (chunk.completionTokens != null) completionTokens = chunk.completionTokens
                    if (chunk.totalTokens != null) totalTokens = chunk.totalTokens
                }
        } catch (e: AiException) {
            fail("LIVE stream FAIL: ${e.message}")
        }
        println(
            "LIVE stream OK: text=$text finish=$finishReason " +
                "prompt=$promptTokens completion=$completionTokens total=$totalTokens",
        )
        assertTrue(text.isNotBlank(), "流式回复为空")
        assertTrue(
            listOf("1", "2", "3", "4", "5").all { text.contains(it) },
            "流式回复内容与提问不符：$text",
        )
        println("LIVE PASS: Anthropic 适配器真机链路（认证 / 非流式 / 流式 / token 统计）全部通过")
    }
}
