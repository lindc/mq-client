package com.mqclient.core.log

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 测 [Log] 的策略面（级别过滤、参数透传、便捷方法）与 JVM 行格式。
 *
 * Log 是进程级单例，所以每个用例自己负责收尾：把 [Log.writer] / [Log.minLevel]
 * 恢复原值（与 CoreModuleSchedulerTest 处理单例的方式一致），不给同一 JVM 里的
 * 其它测试留状态。
 */
class LogTest {

    private data class Captured(
        val timestamp: Long,
        val level: LogLevel,
        val module: String,
        val message: String,
    )

    private val captured = mutableListOf<Captured>()
    private lateinit var previousWriter: LogWriter
    private lateinit var previousMinLevel: LogLevel

    @BeforeTest
    fun setUp() {
        previousWriter = Log.writer
        previousMinLevel = Log.minLevel
        captured.clear()
        Log.writer = LogWriter { timestamp, level, module, message ->
            captured += Captured(timestamp, level, module, message)
        }
        Log.minLevel = LogLevel.DEBUG
    }

    @AfterTest
    fun tearDown() {
        Log.writer = previousWriter
        Log.minLevel = previousMinLevel
    }

    @Test
    fun `log passes module level and message through with a timestamp`() {
        Log.log("ibm-mq", LogLevel.WARN, "scan failed: timeout")

        val record = captured.single()
        assertEquals("ibm-mq", record.module)
        assertEquals(LogLevel.WARN, record.level)
        assertEquals("scan failed: timeout", record.message)
        assertTrue(record.timestamp > 0L, "时间戳必须被填上")
    }

    @Test
    fun `calls below minLevel are dropped`() {
        Log.minLevel = LogLevel.WARN

        Log.debug("ibm-mq", "noise")
        Log.info("ibm-mq", "also noise")
        Log.warn("ibm-mq", "kept")
        Log.error("ibm-mq", "kept too")

        assertEquals(listOf(LogLevel.WARN, LogLevel.ERROR), captured.map { it.level })
    }

    @Test
    fun `convenience helpers map to their levels`() {
        Log.debug("m", "d")
        Log.info("m", "i")
        Log.warn("m", "w")
        Log.error("m", "e")

        assertEquals(
            listOf(LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            captured.map { it.level },
        )
        assertEquals(listOf("d", "i", "w", "e"), captured.map { it.message })
    }

    @Test
    fun `withTag handle stamps the module on every call`() {
        val handle = Log.withTag("ibm-mq")

        handle.info("hello")
        handle.warn("careful")

        assertEquals(listOf("ibm-mq", "ibm-mq"), captured.map { it.module })
        assertEquals(listOf(LogLevel.INFO, LogLevel.WARN), captured.map { it.level })
        assertEquals("ibm-mq", handle.module)
    }

    @Test
    fun `formatted line is time level module message`() {
        val line = formatLogLine(0L, LogLevel.INFO, "ibm-mq", "scan completed")

        // 时间部分随本地时区变化，只锁形状：HH:mm:ss.SSS [INFO] [ibm-mq] scan completed
        assertTrue(
            Regex("""\d{2}:\d{2}:\d{2}\.\d{3} \[INFO] \[ibm-mq] scan completed""").matches(line),
            "行格式不对: $line",
        )
    }

    @Test
    fun `platform writer sends info to stdout and error to stderr`() {
        // 真实写入内核的一次冒烟：INFO → stdout、ERROR → stderr。
        // 交换全局流有风险，所以严格 try/finally 恢复；用例串行执行，不会串到别的测试。
        // emitPlatformLogLine 现在还会写文件 sink（configDir()/logs/），所以这里把
        // 配置目录重定向到临时目录，不让测试碰用户真实的 ~/.mqclient。
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        val previousConfigDir = System.getProperty("mqclient.config.dir")
        val tempDir = Files.createTempDirectory("mqclient-log-test")
        System.setProperty("mqclient.config.dir", tempDir.toString())
        try {
            System.setOut(PrintStream(out, true, Charsets.UTF_8))
            System.setErr(PrintStream(err, true, Charsets.UTF_8))

            // setUp 换掉的是捕获 writer；这里临时换回平台实现，走真实写入路径。
            Log.writer = LogWriter { timestamp, level, module, message ->
                emitPlatformLogLine(timestamp, level, module, message)
            }
            Log.info("smoke", "hello")
            Log.error("smoke", "boom")
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
            if (previousConfigDir == null) {
                System.clearProperty("mqclient.config.dir")
            } else {
                System.setProperty("mqclient.config.dir", previousConfigDir)
            }
            tempDir.toFile().deleteRecursively()
        }

        assertTrue(out.toString(Charsets.UTF_8).contains("[INFO] [smoke] hello"), "INFO 应写 stdout")
        assertTrue(err.toString(Charsets.UTF_8).contains("[ERROR] [smoke] boom"), "ERROR 应写 stderr")
    }
}
