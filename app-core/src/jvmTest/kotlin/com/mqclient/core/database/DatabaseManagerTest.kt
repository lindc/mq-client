package com.mqclient.core.database

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * 守住 [DatabaseManager] 的线程契约：**这条 JDBC 连接绝不能被并发访问**。
 *
 * 用真驱动（临时目录里的 SQLite 文件）跑：假 [app.cash.sqldelight.db.SqlDriver] 测不出
 * "连接被两个线程同时使用"这件事 —— 那正是 sqlite-jdbc 会抛 SQLException / 数据错乱的场景。
 *
 * 临时目录通过 `mqclient.config.dir` 覆盖（[DriverFactory] 现在跟 `ConfigManager.configDir()`
 * 走同一个目录），所以不会碰用户真实的 `~/.mqclient`。
 */
class DatabaseManagerTest {

    private lateinit var dir: File
    private var previousOverride: String? = null

    @BeforeTest
    fun setUp() {
        previousOverride = System.getProperty(DIR_PROPERTY)
        dir = Files.createTempDirectory("mqclient-db-").toFile()
        System.setProperty(DIR_PROPERTY, dir.absolutePath)
        // 前一个用例可能留下已初始化的状态。
        DatabaseManager.close()
    }

    @AfterTest
    fun tearDown() {
        DatabaseManager.close()
        val previous = previousOverride
        if (previous == null) System.clearProperty(DIR_PROPERTY) else System.setProperty(DIR_PROPERTY, previous)
        dir.deleteRecursively()
    }

    @Test
    fun `concurrent callers never touch the driver at the same time`() = runBlocking {
        DatabaseManager.initialize(DriverFactory(), Dispatchers.IO.limitedParallelism(1))

        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        (1..32).map { index ->
            async(Dispatchers.Default) {
                DatabaseManager.withDriver { driver ->
                    val now = inFlight.incrementAndGet()
                    maxInFlight.updateAndGet { max -> maxOf(max, now) }
                    // 建 32 张表：真往同一条连接上打 SQL，而不是空转。
                    driver.execute(null, "CREATE TABLE IF NOT EXISTS t$index (id INTEGER)", 0).value
                    inFlight.decrementAndGet()
                }
            }
        }.awaitAll()

        assertEquals(
            1,
            maxInFlight.get(),
            "同一时刻只能有一个任务碰这条连接（否则 sqlite-jdbc 会报错或写坏数据）",
        )
    }

    @Test
    fun `initialize is idempotent and keeps the first driver`() = runBlocking {
        DatabaseManager.initialize(DriverFactory(), Dispatchers.IO.limitedParallelism(1))
        val first = DatabaseManager.withDriver { it }
        val firstContext = DatabaseManager.dbContext

        DatabaseManager.initialize(DriverFactory(), Dispatchers.IO.limitedParallelism(1))

        assertEquals(
            first,
            DatabaseManager.withDriver { it },
            "重复 initialize 不该换掉已建好的连接",
        )
        assertEquals(firstContext, DatabaseManager.dbContext, "驱动与调度器要成对地只初始化一次")
    }

    @Test
    fun `accessing the driver before initialize fails loudly`() = runBlocking<Unit> {
        DatabaseManager.close()

        assertFailsWith<IllegalStateException> { DatabaseManager.withDriver { it } }
        assertFailsWith<IllegalStateException> { DatabaseManager.dbContext }
    }

    @Test
    fun `close is idempotent and drops the connection`() = runBlocking<Unit> {
        DatabaseManager.initialize(DriverFactory(), Dispatchers.IO.limitedParallelism(1))
        DatabaseManager.close()
        DatabaseManager.close()

        assertFailsWith<IllegalStateException> { DatabaseManager.withDriver { it } }
    }

    private companion object {
        const val DIR_PROPERTY = "mqclient.config.dir"
    }
}
