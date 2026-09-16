package com.mqclient.aipanel

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
            onChunk: (String) -> Unit,
        ) {
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
        withTimeout(2_000) { while (state.messages.last().content.isEmpty()) delay(5) }
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
        initialMessages = emptyList(),
        chat = chat,
        scope = CoroutineScope(SupervisorJob()),
    )

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
