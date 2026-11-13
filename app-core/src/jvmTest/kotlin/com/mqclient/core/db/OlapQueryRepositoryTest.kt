package com.mqclient.core.db

import app.cash.sqldelight.db.QueryResult
import com.mqclient.core.database.DatabaseManager
import com.mqclient.core.database.DriverFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `olap_query_history` / `olap_draft` 的真实 SQLite 往返（临时库，不触用户配置）。
 *
 * 钉住三条交付要求：① 成功 / 失败都落历史；② 草稿覆盖写 + 读回；
 * ③ **表里没有任何结果集列**（行数 / 列值 / ResultSet 都不落库）。
 */
class OlapQueryRepositoryTest {

    private lateinit var dir: File
    private var previousOverride: String? = null

    @BeforeTest
    fun setUp() {
        previousOverride = System.getProperty(DIR_PROPERTY)
        dir = Files.createTempDirectory("mqclient-olap-").toFile()
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

    @Test
    fun `history records success and failure without any result set column`() = runBlocking {
        DatabaseManager.initialize(DriverFactory(), Dispatchers.IO.limitedParallelism(1))

        OlapQueryRepository.save(
            OlapQueryRecord(
                id = "rec-1", connectionId = "conn-1", sqlText = "SELECT 1",
                status = OlapQueryRecord.STATUS_SUCCESS, durationMs = 12L,
                errorMessage = null, createdAt = 1_000L,
            ),
        )
        OlapQueryRepository.save(
            OlapQueryRecord(
                id = "rec-2", connectionId = "conn-1", sqlText = "SELEC bad",
                status = OlapQueryRecord.STATUS_FAILED, durationMs = 3L,
                errorMessage = "syntax error at line 1", createdAt = 2_000L,
            ),
        )

        val recent = OlapQueryRepository.recent("conn-1")
        assertEquals(2, recent.size)
        assertEquals("rec-2", recent[0].id, "按 created_at DESC 返回（最新在前）")
        assertEquals(OlapQueryRecord.STATUS_FAILED, recent[0].status)
        assertEquals("syntax error at line 1", recent[0].errorMessage)
        assertEquals("SELEC bad", recent[0].sqlText)
        assertEquals(3L, recent[0].durationMs)
        assertEquals("rec-1", recent[1].id)
        assertNull(recent[1].errorMessage)
        assertTrue(OlapQueryRepository.recent("other-conn").isEmpty(), "按连接隔离")

        // 「绝对不要保存查询结果集」：表里不允许出现任何结果类列。
        val columns = DatabaseManager.withDriver { driver ->
            driver.executeQuery(
                null,
                "SELECT name FROM pragma_table_info('olap_query_history')",
                { cursor ->
                    val names = mutableListOf<String>()
                    while (cursor.next().value) names += cursor.getString(0).orEmpty()
                    QueryResult.Value(names)
                },
                0,
            ).value
        }
        assertFalse(columns.any { it.contains("result") || it.contains("row") || it.contains("column") },
            "历史表只审计执行事实，不得存结果集：$columns")
    }

    @Test
    fun `draft is overwritten per connection and read back after reopen`() = runBlocking {
        DatabaseManager.initialize(DriverFactory(), Dispatchers.IO.limitedParallelism(1))

        assertNull(OlapQueryRepository.loadDraft("conn-1"))
        OlapQueryRepository.saveDraft("conn-1", "SELECT 1;\nSELECT 2;")
        assertEquals("SELECT 1;\nSELECT 2;", OlapQueryRepository.loadDraft("conn-1"))

        // 覆盖写：一个连接只保留一份最新草稿。
        OlapQueryRepository.saveDraft("conn-1", "SELECT 3;")
        assertEquals("SELECT 3;", OlapQueryRepository.loadDraft("conn-1"))
        assertNull(OlapQueryRepository.loadDraft("conn-2"), "不同连接互不覆盖")
    }

    private companion object {
        const val DIR_PROPERTY = "mqclient.config.dir"
    }
}
