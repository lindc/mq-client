package com.mqclient.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 覆盖「配置读不出来时不能拿默认值覆盖磁盘」这条数据丢失链。
 *
 * 每个用例都用 `mqclient.config.dir`（[ConfigManager.configDir] 的 test hook）
 * 把配置目录重定向到临时目录，不碰真实的 `~/.mqclient`。
 */
class ConfigManagerTest {

    private lateinit var dir: File
    private var previousOverride: String? = null

    @BeforeTest
    fun setUp() {
        previousOverride = System.getProperty(DIR_PROPERTY)
        dir = Files.createTempDirectory("mqclient-config-").toFile()
        System.setProperty(DIR_PROPERTY, dir.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        val previous = previousOverride
        if (previous == null) System.clearProperty(DIR_PROPERTY) else System.setProperty(DIR_PROPERTY, previous)
        dir.deleteRecursively()
    }

    // ---------------------------------------------------------------- 读取

    @Test
    fun `missing config file reads as defaults`() {
        assertNotNull(ConfigManager.loadOrNull(), "文件缺失属于首次启动，不该当成失败")
        assertTrue(ConfigManager.load().connections.isEmpty())
    }

    @Test
    fun `corrupt config file is reported as unreadable`() {
        ConfigManager.save(Config(connections = listOf(connection("mq-01", "first"))))
        writeRaw("this is not json")

        assertNull(ConfigManager.loadOrNull(), "文件在但读不出来必须返回 null，而不是默认值")
        assertTrue(ConfigManager.load().connections.isEmpty(), "load() 仍给默认值，但只能用于启动首帧")
    }

    // ------------------------------------------------------- 读-改-写不覆盖

    @Test
    fun `read-modify-write is abandoned when config is unreadable`() {
        ConfigManager.save(Config(connections = listOf(connection("mq-01", "first"))))
        writeRaw("this is not json")
        val corrupted = readRaw()

        val current = ConfigStore.loadConfigOrNull()
        if (current != null) ConfigStore.saveConfig(current.copy(connections = listOf(connection("mq-02", "second"))))

        assertNull(current)
        assertEquals(corrupted, readRaw(), "读不出来时绝不能写盘 —— 否则整份配置被默认值抹掉")
    }

    @Test
    fun `saveConnections refuses to write when config is unreadable`() {
        ConfigManager.save(Config(connections = listOf(connection("mq-01", "first"))))
        writeRaw("this is not json")
        val corrupted = readRaw()

        val result = ConfigStore.saveConnections(listOf(connection("mq-02", "second")))

        assertTrue(result.isFailure, "配置读不出来时必须以失败返回，而不是假装成功")
        assertEquals(corrupted, readRaw(), "连接列表写不进去可以接受，把其它配置段抹掉不行")
    }

    @Test
    fun `read-modify-write keeps the rest of the config`() {
        ConfigManager.save(Config(connections = listOf(connection("mq-01", "first"))))

        val current = ConfigStore.loadConfigOrNull()
        assertNotNull(current)
        ConfigStore.saveConfig(current.copy(connections = current.connections + connection("mq-02", "second")))

        val reloaded = ConfigStore.loadConfig()
        assertEquals(listOf("mq-01", "mq-02"), reloaded.connections.map { it.id })
    }

    @Test
    fun `concurrent read-modify-writes do not overwrite each other`() {
        ConfigManager.save(Config(connections = listOf(connection("mq-01", "first"))))
        val insideEdit = CountDownLatch(1)
        val releaseEdit = CountDownLatch(1)
        val otherHasRead = CountDownLatch(1)

        // A：读到配置之后停在 transform 里，给另一个写入者留出插队的机会。
        val first = thread(name = "edit-a") {
            ConfigManager.edit { current ->
                insideEdit.countDown()
                releaseEdit.await(10, TimeUnit.SECONDS)
                Result.success(current.copy(connections = current.connections + connection("mq-02", "second")))
            }
        }
        assertTrue(insideEdit.await(10, TimeUnit.SECONDS), "edit 应该已经读到配置并停在 transform 里")

        // B：同样的读-改-写。它要是在 A 的「读」和「写」之间读到了旧值，A 的写入就会把
        // 它的改动整段覆盖 —— 这正是没有原子性时丢一整段连接的路径。
        val second = thread(name = "edit-b") {
            ConfigManager.edit { current ->
                otherHasRead.countDown()
                Result.success(current.copy(connections = current.connections + connection("mq-03", "third")))
            }
        }
        // 尽力让 B 真的插进来。有锁时它会在 edit 入口排队、这里等到超时 —— 那正是要的
        // 结果，所以不校验返回值。
        otherHasRead.await(1, TimeUnit.SECONDS)
        releaseEdit.countDown()
        first.join(10_000)
        second.join(10_000)

        assertEquals(
            listOf("mq-01", "mq-02", "mq-03"),
            ConfigStore.loadConfig().connections.map { it.id },
            "两次读-改-写都得留下：谁都不该被对方整段覆盖",
        )
    }

    // ---------------------------------------------------------------- 备份

    @Test
    fun `save leaves the previous config in the backup file`() {
        ConfigManager.save(Config(connections = listOf(connection("mq-01", "first"))))
        assertEquals(false, backupFile().exists(), "首次保存没有上一版可留")

        ConfigManager.save(Config(connections = listOf(connection("mq-02", "second"))))

        assertTrue(backupFile().exists(), "第二次保存必须留下上一版")
        assertTrue(readRaw().contains("second"), "config.json 是新值")
        assertTrue(backupFile().readText().contains("first"), "config.json.bak 是上一版")
    }

    // ---------------------------------------------------------------- 工具

    private fun connection(id: String, name: String) = ConnectionInfo(
        id = id,
        name = name,
        serviceId = "ibm-mq",
        mqName = "QM1",
        host = "localhost",
        port = 1414,
    )

    private fun configFile(): File = File(dir, CONFIG_FILE_NAME)

    private fun backupFile(): File = File(dir, BACKUP_FILE_NAME)

    private fun writeRaw(text: String) {
        configFile().writeText(text)
    }

    private fun readRaw(): String = configFile().readText()

    private companion object {
        const val DIR_PROPERTY = "mqclient.config.dir"
        const val CONFIG_FILE_NAME = "config.json"
        const val BACKUP_FILE_NAME = "config.json.bak"
    }
}
