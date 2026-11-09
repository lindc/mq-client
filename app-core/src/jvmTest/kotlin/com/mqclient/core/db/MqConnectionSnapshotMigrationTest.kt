package com.mqclient.core.db

import app.cash.sqldelight.db.QueryResult
import com.mqclient.core.database.DatabaseManager
import com.mqclient.core.database.DriverFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * `mq_connection` 旧结构迁移的**原子性**。
 *
 * 旧实现是四步裸 `driver.execute`（RENAME → CREATE → INSERT…SELECT → DROP），没有事务。
 * 任意一步崩溃（掉电 / 磁盘满 / 进程被杀）都会留下半完成状态：`mq_connection` 不存在、
 * `mq_connection_legacy` 成孤儿。下次启动探测不到 `connection_id` 列，迁移被**永久跳过**，
 * `Schema.create` 建出一张空表 —— 用户的历史快照无声蒸发。
 *
 * 这里用一个必然失败的迁移（旧表 `type` 为 NULL，新表 NOT NULL）触发回滚，然后修好数据
 * 重试。两步都过，才说明「失败不留痕、重试能成功」。
 */
class MqConnectionSnapshotMigrationTest {

    private lateinit var dir: File
    private var previousOverride: String? = null

    @BeforeTest
    fun setUp() {
        previousOverride = System.getProperty(DIR_PROPERTY)
        dir = Files.createTempDirectory("mqclient-migration-").toFile()
        System.setProperty(DIR_PROPERTY, dir.absolutePath)
        DatabaseManager.close()
    }

    @AfterTest
    fun tearDown() {
        DatabaseManager.close()
        val previous = previousOverride
        if (previous == null) System.clearProperty(DIR_PROPERTY) else System.setProperty(DIR_PROPERTY, previous)
        dir.deleteRecursively()
    }

    private fun sql(driver: app.cash.sqldelight.db.SqlDriver, statement: String) {
        driver.execute(null, statement, 0).value
    }

    private fun legacyColumns(): List<String> = runBlocking {
        DatabaseManager.withDriver { driver ->
            driver.executeQuery(
                null,
                "SELECT name FROM pragma_table_info('mq_connection')",
                { cursor ->
                    val names = mutableListOf<String>()
                    while (cursor.next().value) names += cursor.getString(0).orEmpty()
                    QueryResult.Value(names)
                },
                0,
            ).value
        }
    }

    @Test
    fun `a failed migration rolls back and a later retry succeeds`() = runBlocking {
        DatabaseManager.initialize(DriverFactory(), Dispatchers.IO.limitedParallelism(1))

        // 旧结构：id INTEGER 自增 + connection_id 列 + created_time。
        DatabaseManager.withDriver { driver ->
            sql(
                driver,
                """
                CREATE TABLE mq_connection (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    connection_id TEXT,
                    name TEXT,
                    type TEXT,
                    host TEXT,
                    port INTEGER,
                    target TEXT,
                    user_name TEXT,
                    created_time INTEGER
                )
                """.trimIndent(),
            )
            // type 为 NULL → 迁移的 INSERT…SELECT 会撞上新表 type NOT NULL。
            sql(
                driver,
                """
                INSERT INTO mq_connection(connection_id, name, type, host, port, target, user_name, created_time)
                VALUES ('conn-1', 'QM1', NULL, 'host', 1414, 'qm1', 'app', 1700000000000)
                """.trimIndent(),
            )
        }

        // 1) 迁移失败：异常上抛，磁盘必须回到「旧表原样」。
        assertFailsWith<Throwable> { MqConnectionSnapshotRepository.getAll() }
        val afterFailure = legacyColumns()
        assertTrue(
            "connection_id" in afterFailure,
            "迁移失败后旧表必须原样存在（回滚），实际列=$afterFailure。" +
                "若只剩新表，说明 DDL 已经提交，旧数据成了孤儿。",
        )

        // 2) 修好坏数据后重试：迁移必须仍然会被尝试（说明没有留下半完成标记）。
        DatabaseManager.withDriver { driver ->
            sql(driver, "UPDATE mq_connection SET type = 'ibm-mq' WHERE connection_id = 'conn-1'")
        }

        val rows = MqConnectionSnapshotRepository.getAll()
        assertEquals(1, rows.size, "重试后旧数据应被平移进新表")
        assertEquals("conn-1", rows.single().id)
        assertEquals("ibm-mq", rows.single().type)
        assertEquals(1700000000000L, rows.single().createdAt)

        val afterSuccess = legacyColumns()
        assertTrue("connection_id" !in afterSuccess, "迁移成功后不该再留旧列，实际列=$afterSuccess")
    }

    private companion object {
        const val DIR_PROPERTY = "mqclient.config.dir"
    }
}
