package com.mqclient.aiui

import com.mqclient.core.AiConfig
import com.mqclient.core.AiConnection
import com.mqclient.core.Config
import com.mqclient.core.ConfigManager
import com.mqclient.core.ConfigStore
import com.mqclient.core.ConnectionInfo
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 守住 [AiPanelState.updateConfig] 的落盘路径：它写的是整份 `config.toml`，
 * 配置读不出来时必须放弃写入，不能拿默认值把连接列表一起抹掉。
 */
class AiPanelStateTest {

    private lateinit var dir: File
    private var previousOverride: String? = null

    @BeforeTest
    fun setUp() {
        previousOverride = System.getProperty(DIR_PROPERTY)
        dir = Files.createTempDirectory("mqclient-ai-").toFile()
        System.setProperty(DIR_PROPERTY, dir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        val previous = previousOverride
        if (previous == null) System.clearProperty(DIR_PROPERTY) else System.setProperty(DIR_PROPERTY, previous)
        dir.deleteRecursively()
    }

    @Test
    fun `updateConfig writes through without dropping other sections`() {
        ConfigManager.save(Config(connections = listOf(connection("mq-01"))))

        val state = newState()
        state.updateConfig { it.copy(historyLimit = 42) }

        assertEquals(42, ConfigStore.loadConfig().ai.historyLimit, "改动要落盘")
        assertEquals(listOf("mq-01"), ConfigStore.loadConfig().connections.map { it.id }, "连接列表不能丢")
        assertNull(state.panelError)
    }

    @Test
    fun `updateConfig gives up when the config is unreadable`() {
        ConfigManager.save(Config(connections = listOf(connection("mq-01"))))
        File(dir, CONFIG_FILE_NAME).writeText("this is not toml")
        val corrupted = File(dir, CONFIG_FILE_NAME).readText()

        val state = newState()
        state.updateConfig { it.copy(historyLimit = 42) }

        assertEquals(corrupted, File(dir, CONFIG_FILE_NAME).readText(), "读不出来时一个字节都不该写")
        assertEquals(42, state.config.historyLimit, "内存里仍然是用户刚选的值")
        assertNotNull(state.panelError, "失败要能提示，不能静默")
    }

    /**
     * 吐一个分片之后永久挂起的后端。
     *
     * 用它是为了让「取消」这条路径不依赖真实时间：不取消它就永远结束不了，所以断言
     * 「停下来了」一定是 [AiPanelState.cancelStreaming] 起的作用，而不是「刚好流完了」。
     */
    private class HangingChatService : ChatService {
        override suspend fun streamReply(
            history: List<AiMessage>,
            connection: AiConnection,
            context: AiContext?,
            systemPromptOverride: String?,
            onChunk: (String) -> Unit,
        ): AiStreamResult {
            onChunk(PARTIAL_REPLY)
            awaitCancellation()
        }
    }

    @Test
    fun `cancelStreaming stops the reply, keeps what streamed and is not an error`() = runBlocking {
        val state = newState(
            config = AiConfig(connections = listOf(aiConnection())),
            chat = HangingChatService(),
        )

        state.sendUserMessage("你好", null)
        // 等第一个分片真的追加进气泡：抢在它之前取消的话一个字都还没落地。
        // （消息在 sendUserMessage 协程里、历史加载完成之后才追加，列表可能短暂为空。）
        withTimeout(2_000) { while (state.messages.isEmpty() || state.messages.last().content.isEmpty()) delay(5) }
        assertTrue(state.isStreaming, "回复没结束前应该在流式")

        state.cancelStreaming()

        withTimeout(2_000) { while (state.isStreaming) delay(5) }
        assertEquals(PARTIAL_REPLY, state.messages.last().content, "点停止前看到的那部分要留着")
        assertNull(state.streamingMessageId)
        assertNull(state.panelError, "取消不是失败，不该弹错误提示")
    }

    private fun newState(
        config: AiConfig = AiConfig(),
        chat: ChatService = MockChatService(),
    ): AiPanelState = AiPanelState(
        initialConfig = config,
        chat = chat,
        scope = CoroutineScope(SupervisorJob()),
    )

    /**
     * 记录后端：把真正传给模型的 messages / 系统提示词留下来。
     * 「不可信边界」是发送时点拼的，只能在这里断言。
     */
    /**
     * 流式节流后**内容仍完整**：服务端高速吐字（分片间隔远小于 100ms）时，UI 只
     * 刷新了少数几次，但收尾必须把每一个字都写回去 —— 节流不能吞内容。
     */
    @Test
    fun `throttled streaming keeps every chunk`() = runBlocking {
        val chunks = (1..200).map { "chunk-$it " }
        val state = newState(
            config = AiConfig(connections = listOf(aiConnection())),
            chat = object : ChatService {
                override suspend fun streamReply(
                    history: List<AiMessage>,
                    connection: AiConnection,
                    context: AiContext?,
                    systemPromptOverride: String?,
                    onChunk: (String) -> Unit,
                ): AiStreamResult {
                    chunks.forEach(onChunk) // 无延迟：全部落在同一个 100ms 窗口内
                    return AiStreamResult(finishReason = "stop")
                }
            },
        )

        state.sendUserMessage("你好", null)
        withTimeout(5_000) { while (state.isStreaming) delay(5) }

        assertEquals(chunks.joinToString(""), state.messages.last().content, "节流不能丢任何分片")
    }

    private class RecordingChatService : ChatService {
        var lastHistory: List<AiMessage> = emptyList()
        var lastSystemPrompt: String? = null

        override suspend fun streamReply(
            history: List<AiMessage>,
            connection: AiConnection,
            context: AiContext?,
            systemPromptOverride: String?,
            onChunk: (String) -> Unit,
        ): AiStreamResult {
            lastHistory = history
            lastSystemPrompt = systemPromptOverride
            onChunk("ok")
            return AiStreamResult(finishReason = "stop")
        }
    }

    /**
     * 链路分析的 wire 消息必须包在不可信边界里（方案 A：整段上下文一个边界），
     * 而边界内仍是完整 JSON；系统提示词同时带「标记内是数据」的规则。
     */
    @Test
    fun `chain analysis wraps the wire context in the untrusted boundary`() = runBlocking {
        val recorder = RecordingChatService()
        val state = newState(config = AiConfig(connections = listOf(aiConnection())), chat = recorder)
        val chain = AiChainTarget(
            connectionId = "mq-01",
            objectType = "QUEUE",
            objectName = "ORDER.Q",
            targetLabel = "ORDER.Q",
            contextJson = """[{"name":"ORDER.Q","type":"QUEUE","isTarget":true}]""",
        )

        state.sendUserMessage(
            "为什么堆积？",
            AiContext(connectionName = "mq-01", serviceId = "ibm-mq", objectType = "QUEUE", chain = chain),
        )
        withTimeout(2_000) { while (state.isStreaming) delay(5) }

        val wire = recorder.lastHistory.last { it.role == Role.USER }.content
        assertTrue(wire.startsWith(UNTRUSTED_CONTEXT_BEGIN), "发给模型的上下文必须被边界包住：$wire")
        assertTrue(wire.endsWith(UNTRUSTED_CONTEXT_END), "边界必须闭合：$wire")

        // 边界内仍是完整 JSON（currentTime / chain / userQuestion 三件都在）。
        val inner = wire.removePrefix(UNTRUSTED_CONTEXT_BEGIN).removeSuffix(UNTRUSTED_CONTEXT_END).trim()
        assertTrue(inner.contains("\"chain\""), "边界内应是上下文 JSON：$inner")
        assertTrue(inner.contains("\"userQuestion\":\"为什么堆积？\""), "用户问题也要进边界：$inner")
        assertTrue(inner.contains("\"currentTime\""), "context_json 结构不变：$inner")

        val systemPrompt = assertNotNull(recorder.lastSystemPrompt)
        assertTrue(systemPrompt.contains(UNTRUSTED_CONTEXT_BEGIN), "系统提示词必须声明边界：$systemPrompt")
    }

    private fun aiConnection() = AiConnection(
        id = "a1",
        name = "A",
        providerId = "openai-compatible",
        baseUrl = "http://localhost:11434/v1",
    )

    private fun connection(id: String) = ConnectionInfo(
        id = id,
        name = id,
        serviceId = "ibm-mq",
        mqName = "QM1",
        host = "localhost",
        port = 1414,
    )

    private companion object {
        const val DIR_PROPERTY = "mqclient.config.dir"
        const val CONFIG_FILE_NAME = "config.toml"
        const val PARTIAL_REPLY = "已流出的部分"
    }
}
