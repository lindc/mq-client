package com.mqclient.rocketmq

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 回归守卫：RocketMQ 的日志**绝不允许**落到 `${user.home}/logs/rocketmqlogs`。
 *
 * 现场问题：`~/logs/rocketmqlogs/tools.log` + `tools_default.log` 反复出现，前两轮修复
 * 都只改 `main()` 里的 setProperty 时机 —— 那只覆盖「应用主进程」。真正的漏洞是
 * **任何带 RocketMQ 的 classpath**：`rocketmq-tools-5.3.1.jar` 的
 * `rmq.tools.logback.xml` 把 `<file>` 写死成 `${user.home}/logs/rocketmqlogs`，
 * 只要没有更靠前的 `rmq.logback.xml`，shaded logback 就会选中它并当场建出这两个文件。
 * `:rocketmq-service:test` 自己就会踩中（它没有 app-ui 的资源）。
 *
 * 所以这里 fork 独立 JVM（隔离 `user.home`，并且**不加载本测试 JVM 已有的
 * LoggerContext**），断言的是文件系统事实：
 *  - `<home>/.mqclient/logs/rocketmqlogs/` 里有日志文件；
 *  - `<home>/logs/` **不存在**。
 *
 * 两个模式各测一遍：
 *  - `bare`：连 install() 都不调，只靠 classpath 上的 `rmq.logback.xml` —— 覆盖测试 /
 *    工具 / CLI 这些没有 `main()` 的 JVM；
 *  - `install`：先 install() 再打日志 —— 覆盖应用主进程的路径，且验证属性全部指向新落点。
 */
class RocketMqLogsStayOutOfHomeTest {

    private class ProbeResult(
        val exitCode: Int,
        val output: String,
        val home: File,
    ) {
        val logDir: File get() = File(home, ".mqclient/logs/rocketmqlogs")
        val forbiddenHomeLogs: File get() = File(home, "logs")

        fun prop(key: String): String? =
            output.lineSequence()
                .firstOrNull { it.startsWith("PROP_$key=") }
                ?.substringAfter('=')
                ?.takeIf { it != "null" }

        fun line(prefix: String): String? =
            output.lineSequence().firstOrNull { it.startsWith("$prefix=") }?.substringAfter('=')
    }

    private fun runProbe(mode: String): ProbeResult {
        val home = createTempDirectory("rmq-log-home").toFile()
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path")
        val proc = ProcessBuilder(
            java,
            "-Duser.home=${home.absolutePath}",
            "-Dmqclient.config.dir=${File(home, ".mqclient").absolutePath}",
            "-cp",
            classpath,
            RocketMqLogProbe::class.java.name,
            mode,
        )
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        assertTrue(proc.waitFor(120, TimeUnit.SECONDS), "探针 JVM 超时\n$output")
        return ProbeResult(proc.exitValue(), output, home)
    }

    @Test
    fun `without install the bundled logback config still keeps logs out of user home`() {
        val result = runProbe("bare")
        assertEquals(0, result.exitCode, "探针进程失败：\n${result.output}")
        assertTrue(result.output.contains("PROBE_DONE"), "探针未跑完：\n${result.output}")

        assertTrue(
            File(result.logDir, "rocketmq_client.log").isFile,
            "日志必须落在 ~/.mqclient/logs/rocketmqlogs，实际目录：${result.logDir.list()?.toList()}\n${result.output}",
        )
        assertFalse(
            result.forbiddenHomeLogs.exists(),
            "不得在用户根目录建 logs/：${result.forbiddenHomeLogs.list()?.toList()}\n${result.output}",
        )
        assertFalse(
            File(result.logDir, "tools.log").exists(),
            "tools.log 说明生效的是 rocketmq-tools 写死路径的那份配置（本次修复的核心回归）",
        )
    }

    @Test
    fun `install pins every rocketmq log property to the config dir`() {
        val result = runProbe("install")
        assertEquals(0, result.exitCode, "探针进程失败：\n${result.output}")

        val expectedRoot = File(result.home, ".mqclient/logs/rocketmqlogs").absolutePath
        assertEquals(expectedRoot, result.line("LOG_ROOT"), "install() 返回值\n${result.output}")
        assertEquals(expectedRoot, result.prop("rocketmq.log.root"), "5.x 口径\n${result.output}")
        assertEquals(expectedRoot, result.prop("rocketmq.client.logRoot"), "4.x 兼容口径\n${result.output}")

        val configUrl = result.line("CONFIG_URL").orEmpty()
        assertTrue(
            configUrl.endsWith(RocketMqLogging.CONFIG_RESOURCE),
            "rmq.logback.configurationFile 必须指向应用自带的 ${RocketMqLogging.CONFIG_RESOURCE}，实际 $configUrl",
        )

        assertTrue(File(result.logDir, "rocketmq_client.log").isFile, "日志文件缺失\n${result.output}")
        assertFalse(result.forbiddenHomeLogs.exists(), "不得在用户根目录建 logs/\n${result.output}")

        // 探针结束后清理临时 HOME，避免测试反复跑堆积目录。
        result.home.deleteRecursively()
    }
}
