package com.mqclient.aipanel

import com.mqclient.core.AiConfig
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * 守住 [AiPanelState.updateConfig] 的落盘路径：它写的是整份 `config.json`，
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
        File(dir, CONFIG_FILE_NAME).writeText("this is not json")
        val corrupted = File(dir, CONFIG_FILE_NAME).readText()

        val state = newState()
        state.updateConfig { it.copy(historyLimit = 42) }

        assertEquals(corrupted, File(dir, CONFIG_FILE_NAME).readText(), "读不出来时一个字节都不该写")
        assertEquals(42, state.config.historyLimit, "内存里仍然是用户刚选的值")
        assertNotNull(state.panelError, "失败要能提示，不能静默")
    }

    private fun newState(): AiPanelState = AiPanelState(
        initialConfig = AiConfig(),
        initialMessages = emptyList(),
        chat = MockChatService(),
        scope = CoroutineScope(SupervisorJob()),
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
        const val CONFIG_FILE_NAME = "config.json"
    }
}
