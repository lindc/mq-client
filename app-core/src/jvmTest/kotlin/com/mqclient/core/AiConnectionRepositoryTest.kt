package com.mqclient.core

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守住 [AiConnectionRepository] 的两条底线：
 *  - token 落盘必须加密（密文进 config.toml，读回来是明文）；
 *  - 旧模型（`ai.providerConfigs`）的启动迁移不能丢用户数据。
 */
class AiConnectionRepositoryTest {

    private lateinit var dir: File
    private var previousOverride: String? = null

    @BeforeTest
    fun setUp() {
        previousOverride = System.getProperty(DIR_PROPERTY)
        dir = Files.createTempDirectory("mqclient-ai-repo-").toFile()
        System.setProperty(DIR_PROPERTY, dir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        val previous = previousOverride
        if (previous == null) System.clearProperty(DIR_PROPERTY) else System.setProperty(DIR_PROPERTY, previous)
        dir.deleteRecursively()
    }

    @Test
    fun `save encrypts the token on disk and load decrypts it back`() {
        val connection = AiConnection(
            id = "a1",
            name = "OpenAI 主账号",
            providerId = "openai-compatible",
            baseUrl = "https://api.openai.com/v1",
            token = "sk-secret-123",
            model = "gpt-4o",
        )

        assertTrue(AiConnectionRepository.save(listOf(connection)).isSuccess)

        val raw = File(dir, CONFIG_FILE_NAME).readText()
        assertTrue(raw.contains("enc:v1:"), "敏感字段应该以 enc:v1: 密文存储")
        assertFalse(raw.contains("sk-secret-123"), "明文 token 不该出现在磁盘上")

        val loaded = AiConnectionRepository.load().getOrThrow()
        assertEquals(listOf("a1"), loaded.map { it.id })
        assertEquals("sk-secret-123", loaded.first().token)
    }

    @Test
    fun `create update delete keep other config sections intact`() {
        ConfigManager.save(Config(connections = listOf(mqConnection("mq-01"))))
        val first = AiConnection(id = "a1", name = "A", providerId = "claude")
        val second = AiConnection(id = "a2", name = "B", providerId = "openai-compatible")

        assertTrue(AiConnectionRepository.create(first).isSuccess)
        assertTrue(AiConnectionRepository.create(second).isSuccess)
        assertTrue(AiConnectionRepository.create(first).isFailure, "id 重复要失败")

        assertTrue(AiConnectionRepository.update(first.copy(name = "A2")).isSuccess)
        assertEquals("A2", AiConnectionRepository.load().getOrThrow().first { it.id == "a1" }.name)

        // 让 a1 成为激活项，删掉它应把 activeConnectionId 一并清空。
        val withActive = ConfigStore.loadConfig().copy(
            ai = ConfigStore.loadConfig().ai.copy(activeConnectionId = "a1"),
        )
        ConfigManager.save(withActive)
        assertTrue(AiConnectionRepository.delete("a1").isSuccess)
        assertEquals("", ConfigStore.loadConfig().ai.activeConnectionId)
        assertTrue(AiConnectionRepository.delete("a1").isFailure, "再删一次要失败")

        // 其它配置段（MQ 连接列表）原样保留。
        assertEquals(listOf("mq-01"), ConfigStore.loadConfig().connections.map { it.id })
    }

    @Test
    fun `startup migration folds legacy provider configs into connections`() {
        val legacyPrompt = Base64.getEncoder().encodeToString("你好".toByteArray(Charsets.UTF_8))
        ConfigManager.save(
            Config(
                ai = AiConfig(
                    activeProviderId = "claude",
                    providerConfigs = listOf(
                        AiProviderConfig(providerId = "openai", accessToken = "k1"),
                        AiProviderConfig(
                            providerId = "ollama",
                            providerUrl = "http://localhost:11434",
                            model = "qwen2.5",
                        ),
                        AiProviderConfig(providerId = "claude", accessToken = "k2", systemPromptBase64 = legacyPrompt),
                    ),
                ),
            ),
        )

        ConfigManager.initOnStartup()

        val ai = ConfigStore.loadConfig().ai
        assertEquals(3, ai.connections.size, "三条旧槽位应该变成三条连接")
        assertTrue(ai.providerConfigs.isEmpty(), "迁移后旧字段清空")

        val openai = ai.connections.first { it.name == "OpenAI" }
        assertEquals("openai-compatible", openai.providerId)
        assertEquals("https://api.openai.com/v1", openai.baseUrl)
        assertEquals("k1", openai.token, "旧 token 要带过来（读盘已解密）")
        assertEquals("gpt-4o", openai.model, "空缺的 model 用映射默认值补上")

        val ollama = ai.connections.first { it.name == "Ollama" }
        assertEquals("http://localhost:11434/v1", ollama.baseUrl, "Ollama 的 URL 要补 /v1")
        assertEquals("qwen2.5", ollama.model)

        val claude = ai.connections.first { it.name == "Claude" }
        assertEquals("你好", claude.systemPrompt, "旧 base64 提示词要解码成明文")
        assertEquals(claude.id, ai.activeConnectionId, "旧 activeProviderId 对应的连接接任激活项")

        // 幂等：再跑一次启动迁移不再产生变化。
        ConfigManager.initOnStartup()
        assertEquals(3, ConfigStore.loadConfig().ai.connections.size)
    }

    @Test
    fun `load fails loudly when the config file is unreadable`() {
        File(dir, CONFIG_FILE_NAME).writeText("this is not toml")

        val result = AiConnectionRepository.load()

        assertTrue(result.isFailure, "读不出来时必须失败，不能假装没有连接")
    }

    private fun mqConnection(id: String) = ConnectionInfo(
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
    }
}
