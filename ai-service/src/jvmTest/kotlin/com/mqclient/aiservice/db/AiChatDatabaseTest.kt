package com.mqclient.aiservice.db

import com.mqclient.core.database.DatabaseManager
import com.mqclient.core.database.DriverFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 覆盖 AI 落库的两张表（ai_connection 快照链 + chat_history 审计）。
 *
 * 用真驱动（临时目录 SQLite，`mqclient.config.dir` 覆盖，不碰用户真实的
 * `~/.mqclient`）跑，风格对齐 app-core 的 DatabaseManagerTest。
 */
class AiChatDatabaseTest {

    private lateinit var dir: File
    private var previousOverride: String? = null

    @BeforeTest
    fun setUp() {
        previousOverride = System.getProperty(DIR_PROPERTY)
        dir = Files.createTempDirectory("mqclient-aichat-").toFile()
        System.setProperty(DIR_PROPERTY, dir.absolutePath)
        DatabaseManager.close()
        DatabaseManager.initialize(DriverFactory(), Dispatchers.IO.limitedParallelism(1))
        runBlocking { DatabaseManager.withDriver { AiChatDatabase.Schema.create(it) } }
    }

    @AfterTest
    fun tearDown() {
        DatabaseManager.close()
        val previous = previousOverride
        if (previous == null) System.clearProperty(DIR_PROPERTY) else System.setProperty(DIR_PROPERTY, previous)
        dir.deleteRecursively()
    }

    @Test
    fun `snapshot stores the caller-provided prevId and selectLatest returns the newest`() = runBlocking {
        AiConnectionSnapshotRepository.saveSnapshot("new-uuid", prevId = "old-uuid", serviceUrl = "http://b")

        val snapshots = AiConnectionSnapshotRepository.getAllSnapshots("new-uuid")
        assertEquals(1, snapshots.size)
        assertEquals("old-uuid", snapshots.single().prev_id, "prevId = 旧连接的 UUID（跨连接追溯）")
        assertEquals("http://b", snapshots.single().service_url)

        val latest = AiConnectionSnapshotRepository.getLatestSnapshot("new-uuid")
        assertEquals("http://b", latest?.service_url, "selectLatest 拿到的是最新一条")

        assertNull(AiConnectionSnapshotRepository.getLatestSnapshot("missing"), "没有快照的连接返回 null")
    }

    @Test
    fun `saveWithLatestSnapshot points at the newest snapshot`() = runBlocking {
        AiConnectionSnapshotRepository.saveSnapshot("c1", prevId = null, serviceUrl = "http://a")
        ChatHistoryRepository.saveWithLatestSnapshot(
            connectionId = "c1",
            modelName = "gpt-4o",
            question = "q",
            reply = "r",
            tokenCount = 10,
            promptTokens = 7,
            completionTokens = 3,
            createdTime = 1_000L,
            startedTime = 1_050L,
            finishedTime = 1_100L,
            status = ChatHistoryRepository.STATUS_SUCCESS,
            errorMessage = null,
            finishReason = "stop",
        )
        val row = ChatHistoryRepository.getByConnectionId("c1").single()
        assertEquals(AiConnectionSnapshotRepository.getLatestSnapshot("c1")?.connection_id, row.snapshot_id)
        assertEquals(10L, row.token_count)

        // url 变化：旧连接 c1 废弃，新连接 c2 接任（prevId = 旧连接 UUID）；
        // 之后 c2 上的对话要指向 c2 自己的最新快照。
        AiConnectionSnapshotRepository.saveSnapshot("c2", prevId = "c1", serviceUrl = "http://b")
        ChatHistoryRepository.saveWithLatestSnapshot(
            connectionId = "c2",
            modelName = "gpt-4o",
            question = "q2",
            reply = "r2",
            tokenCount = 0,
            promptTokens = 0,
            completionTokens = 0,
            createdTime = 2_000L,
            startedTime = null,
            finishedTime = null,
            status = ChatHistoryRepository.STATUS_SUCCESS,
            errorMessage = null,
            finishReason = null,
        )
        val newRow = ChatHistoryRepository.getByConnectionId("c2").single()
        assertEquals(AiConnectionSnapshotRepository.getLatestSnapshot("c2")?.connection_id, newRow.snapshot_id, "指向新连接自己的快照")
    }

    @Test
    fun `getRecent orders newest first and respects the limit`() = runBlocking {
        repeat(5) { index ->
            ChatHistoryRepository.saveWithLatestSnapshot(
                connectionId = "c1",
                modelName = "m",
                question = "q$index",
                reply = "r$index",
                tokenCount = 0,
                promptTokens = 0,
                completionTokens = 0,
                createdTime = 1_000L + index,
                startedTime = null,
                finishedTime = null,
                status = ChatHistoryRepository.STATUS_SUCCESS,
                errorMessage = null,
                finishReason = null,
            )
        }

        val recent = ChatHistoryRepository.getRecent("c1", limit = 3)
        assertEquals(listOf("q4", "q3", "q2"), recent.map { it.question }, "倒序取最近 N 条")
        assertEquals(5, ChatHistoryRepository.getByConnectionId("c1").size, "全量不受 limit 影响")
    }

    @Test
    fun `model and time-range filters only match their rows`() = runBlocking {
        suspend fun save(model: String, createdTime: Long) = ChatHistoryRepository.saveWithLatestSnapshot(
            connectionId = "c1",
            modelName = model,
            question = "q",
            reply = "r",
            tokenCount = 0,
            promptTokens = 0,
            completionTokens = 0,
            createdTime = createdTime,
            startedTime = null,
            finishedTime = null,
            status = ChatHistoryRepository.STATUS_SUCCESS,
            errorMessage = null,
            finishReason = null,
        )
        save("gpt-4o", 1_000)
        save("gpt-4o", 2_000)
        save("llama3", 3_000)

        assertEquals(2, ChatHistoryRepository.getByModel("c1", "gpt-4o").size)
        assertEquals(
            listOf(2_000L, 1_000L),
            ChatHistoryRepository.getByTimeRange("c1", fromTime = 500, toTime = 2_500).map { it.created_time },
            "闭区间过滤 + 倒序",
        )
    }

    @Test
    fun `cleanOldBefore deletes only older rows and reports the count`() = runBlocking {
        suspend fun save(createdTime: Long) = ChatHistoryRepository.saveWithLatestSnapshot(
            connectionId = "c1",
            modelName = "m",
            question = "q",
            reply = "r",
            tokenCount = 0,
            promptTokens = 0,
            completionTokens = 0,
            createdTime = createdTime,
            startedTime = null,
            finishedTime = null,
            status = ChatHistoryRepository.STATUS_SUCCESS,
            errorMessage = null,
            finishReason = null,
        )
        save(1_000)
        save(2_000)
        save(9_000)

        assertEquals(2, ChatHistoryRepository.cleanOldBefore(5_000), "返回删除行数")
        assertEquals(listOf(9_000L), ChatHistoryRepository.getByConnectionId("c1").map { it.created_time })

        assertEquals(1, ChatHistoryRepository.deleteByConnectionId("c1"), "整连接清理返回行数")
        assertTrue(ChatHistoryRepository.getByConnectionId("c1").isEmpty())
    }

    @Test
    fun `chain analysis fields round-trip and normal chat leaves them null`() = runBlocking {
        // 普通聊天：不传链路字段，三列落 NULL。
        ChatHistoryRepository.saveWithLatestSnapshot(
            connectionId = "c1",
            modelName = "m",
            question = "q",
            reply = "r",
            tokenCount = 0,
            promptTokens = 0,
            completionTokens = 0,
            createdTime = 1_000L,
            startedTime = null,
            finishedTime = null,
            status = ChatHistoryRepository.STATUS_SUCCESS,
            errorMessage = null,
            finishReason = null,
        )
        // 链路分析：三列随事件 payload 落库。
        ChatHistoryRepository.saveWithLatestSnapshot(
            connectionId = "c1",
            modelName = "m",
            question = "这条队列为什么积压",
            reply = "部分回答",
            tokenCount = 0,
            promptTokens = 0,
            completionTokens = 0,
            createdTime = 2_000L,
            startedTime = null,
            finishedTime = 2_500L,
            status = ChatHistoryRepository.STATUS_CANCELLED,
            errorMessage = null,
            finishReason = null,
            objectType = "QUEUE",
            objectName = "ORDER.LOCAL.Q",
            contextJson = """{"currentTime":"..","chain":[],"userQuestion":".."}""",
        )

        val rows = ChatHistoryRepository.getByConnectionId("c1")
        assertEquals(2, rows.size)

        val normal = rows.single { it.created_time == 1_000L }
        assertNull(normal.object_type)
        assertNull(normal.object_name)
        assertNull(normal.context_json)

        val chain = rows.single { it.created_time == 2_000L }
        assertEquals("QUEUE", chain.object_type)
        assertEquals("ORDER.LOCAL.Q", chain.object_name)
        assertEquals("""{"currentTime":"..","chain":[],"userQuestion":".."}""", chain.context_json)
        assertEquals(ChatHistoryRepository.STATUS_CANCELLED, chain.status)
    }

    private companion object {
        const val DIR_PROPERTY = "mqclient.config.dir"
    }
}
