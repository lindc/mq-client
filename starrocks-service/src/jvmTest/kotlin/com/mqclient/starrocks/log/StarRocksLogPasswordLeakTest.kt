package com.mqclient.starrocks.log

import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.ConnectionRegistry
import com.mqclient.core.spi.ServiceContext
import com.mqclient.starrocks.StarRocksConnectionConfig
import com.mqclient.starrocks.StarRocksConnectionManager
import com.mqclient.starrocks.jdbc.FakeJdbcConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「密码永不入日志」的**机制**测试：真跑一次连接（FakeJdbcConnector，不触网），
 * 把标准输出 / 标准错误整段接住，断言里面找不到密码。
 *
 * 为什么值得单独测：日志里漏一次密码就写进本地日志文件（`logs/mqclient.log`），
 * 且会随用户上传日志扩散。约定写在 KDoc 里只是提醒，这条测试才是保证。
 *
 * 覆盖两条最容易泄漏的路径：
 *  1. 配置里的 `password` 字段（谁把它拼进日志）；
 *  2. `jdbcUrlOverride` 里手写的 `password=...` 参数（用户完全可能这么配）。
 */
class StarRocksLogPasswordLeakTest {

    private lateinit var previousOut: PrintStream
    private lateinit var previousErr: PrintStream
    private val captured = ByteArrayOutputStream()

    @BeforeTest
    fun captureStreams() {
        previousOut = System.out
        previousErr = System.err
        val sink = PrintStream(captured, true, Charsets.UTF_8)
        System.setOut(sink)
        System.setErr(sink)
    }

    @AfterTest
    fun restoreStreams() {
        System.setOut(previousOut)
        System.setErr(previousErr)
    }

    private fun testContext(): ServiceContext {
        val scope = CoroutineScope(SupervisorJob())
        val bus = EventBus(scope)
        return ServiceContext.of("starrocks", bus, scope, JobScheduler(scope, bus), ConnectionRegistry.Empty)
    }

    @Test
    fun `password fields never reach the log sink`() = runBlocking {
        val config = StarRocksConnectionConfig(
            connectionId = "leak-check",
            displayName = "leak-check",
            host = "localhost",
            port = 9030,
            user = "root",
            password = "S3cr3t-P@ssw0rd",
            database = "information_schema",
        )

        StarRocksConnectionManager(testContext(), connector = FakeJdbcConnector()).testConnection(config)

        val log = captured.toString(Charsets.UTF_8)
        assertTrue(log.contains("[StarRocks][Conn]"), "应捕获到连接日志：$log")
        assertFalse(log.contains("S3cr3t-P@ssw0rd"), "密码不得出现在日志中：$log")
    }

    @Test
    fun `password query parameter inside jdbcUrlOverride is masked`() = runBlocking {
        val secret = "url-inline-secret"
        val config = StarRocksConnectionConfig(
            connectionId = "leak-check-url",
            displayName = "leak-check-url",
            host = "localhost",
            port = 9030,
            user = "root",
            database = "information_schema",
            jdbcUrlOverride = "jdbc:mysql://db.example:9030/information_schema?password=$secret&connectTimeout=3000",
        )

        StarRocksConnectionManager(testContext(), connector = FakeJdbcConnector()).testConnection(config)

        val log = captured.toString(Charsets.UTF_8)
        assertFalse(log.contains(secret), "URL 里的密码必须被脱敏：$log")
        assertTrue(log.contains("password=***"), "脱敏后应保留参数名占位：$log")
    }
}
