package com.mqclient.rocketmq

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RocketMqClientLogConfigurator 的离线单测：`mqclient.config.dir` 覆盖到临时目录，
 * 不碰真实 `~/.mqclient`。锁三点：目录自动创建、5.x 属性生效（4.x 兼容名同步）、
 * 重复调用幂等且不抛。
 */
class RocketMqClientLogConfiguratorTest {

    @Test
    fun `init creates log dir under config dir and sets properties`() {
        val tmp = Files.createTempDirectory("rmq-logcfg-test")
        System.setProperty("mqclient.config.dir", tmp.toString())
        RocketMqClientLogConfigurator.resetForTest()
        try {
            RocketMqClientLogConfigurator.init()

            val logDir = tmp.resolve("logs").resolve("rocketmqlogs")
            assertTrue(Files.isDirectory(logDir), "log dir should be created: $logDir")

            val expected = logDir.toFile().absolutePath
            assertEquals(expected, System.getProperty("rocketmq.log.root"))
            assertEquals("10", System.getProperty("rocketmq.log.file.maxIndex"))
            assertEquals("INFO", System.getProperty("rocketmq.log.level"))
            // 4.x 兼容名同步设置。
            assertEquals(expected, System.getProperty("rocketmq.client.logRoot"))
            assertEquals("10", System.getProperty("rocketmq.client.logFileMaxIndex"))
        } finally {
            System.clearProperty("mqclient.config.dir")
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `repeated init is idempotent`() {
        val tmp = Files.createTempDirectory("rmq-logcfg-test2")
        System.setProperty("mqclient.config.dir", tmp.toString())
        RocketMqClientLogConfigurator.resetForTest()
        try {
            RocketMqClientLogConfigurator.init()
            val first = System.getProperty("rocketmq.log.root")
            // 再调一次（幂等守卫路径）：属性不被反复覆盖，也不抛。
            RocketMqClientLogConfigurator.init()
            assertEquals(first, System.getProperty("rocketmq.log.root"))
        } finally {
            System.clearProperty("mqclient.config.dir")
            tmp.toFile().deleteRecursively()
        }
    }
}
