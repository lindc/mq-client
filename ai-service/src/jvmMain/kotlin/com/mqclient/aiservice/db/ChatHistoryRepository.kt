package com.mqclient.aiservice.db

import com.mqclient.core.database.DatabaseManager

/**
 * chat_history 对话审计表的读写入口。只插入、不更新；查询按需组合。
 *
 * 所有访问都经 [DatabaseManager.withDriver] 跑在单槽串行调度器上（线程模型见
 * DatabaseManager）；错误处理交给调用方（事件 handler / UI），这里只做纯读写。
 * ai_connectionQueries 与 chat_historyQueries 包着同一个 driver，同一块里混用
 * 两者 = 同一条连接上的相邻语句。
 */
object ChatHistoryRepository {

    const val STATUS_SUCCESS = "SUCCESS"
    const val STATUS_FAILED = "FAILED"

    /**
     * 落一条对话记录。[snapshotId] 在**同一个** withDriver 块里查最新快照得到，
     * 避免快照订阅与对话订阅交错时读到不一致的快照链。业务侧约定：只在 AI 回复
     * 完成后调用一次（成功与失败都算"完成"）。
     */
    suspend fun saveWithLatestSnapshot(
        connectionId: String,
        modelName: String,
        question: String?,
        reply: String?,
        tokenCount: Int,
        promptTokens: Int,
        completionTokens: Int,
        createdTime: Long,
        startedTime: Long?,
        finishedTime: Long?,
        status: String,
        errorMessage: String?,
        finishReason: String?,
    ): Unit = withDatabase { db ->
        val snapshotId = db.ai_connectionQueries
            .selectLatestByConnectionId(connectionId)
            .executeAsOneOrNull()
            ?.id
        db.chat_historyQueries.insertChat(
            connectionId = connectionId,
            snapshotId = snapshotId,
            modelName = modelName,
            // SQLite INTEGER 列生成 Long?；上游 payload 按 Int 约定，这里收口转换。
            tokenCount = tokenCount.toLong(),
            promptTokens = promptTokens.toLong(),
            completionTokens = completionTokens.toLong(),
            createdTime = createdTime,
            startedTime = startedTime,
            finishedTime = finishedTime,
            status = status,
            errorMessage = errorMessage,
            finishReason = finishReason,
            question = question,
            reply = reply,
        )
    }

    /** 某个连接的全部对话（createdTime 倒序）。 */
    suspend fun getByConnectionId(connectionId: String): List<Chat_history> = withDatabase { db ->
        db.chat_historyQueries.selectByConnectionId(connectionId).executeAsList()
    }

    /** 某个连接的最近 [limit] 条对话（createdTime 倒序）。 */
    suspend fun getRecent(connectionId: String, limit: Long): List<Chat_history> = withDatabase { db ->
        db.chat_historyQueries.selectRecentByConnectionId(connectionId = connectionId, limit = limit).executeAsList()
    }

    /** 某个连接里某个模型的全部对话（createdTime 倒序）。 */
    suspend fun getByModel(connectionId: String, modelName: String): List<Chat_history> = withDatabase { db ->
        db.chat_historyQueries.selectByModel(connectionId = connectionId, modelName = modelName).executeAsList()
    }

    /**
     * 某个连接的全部对话，经 snapshot_id 连出所属快照的 serviceUrl（createdTime 倒序）。
     * 生成类型 [SelectWithSnapshot] = Chat_history 全字段 + `snapshotServiceUrl: String?`
     * （LEFT JOIN —— FAILED / 无快照的行 url 为 null，不丢行）。
     */
    suspend fun getWithSnapshot(connectionId: String): List<SelectWithSnapshot> = withDatabase { db ->
        db.chat_historyQueries.selectWithSnapshot(connectionId).executeAsList()
    }

    /** 某个连接在 [fromTime, toTime] 闭区间内的对话（createdTime 倒序）。 */
    suspend fun getByTimeRange(connectionId: String, fromTime: Long, toTime: Long): List<Chat_history> =
        withDatabase { db ->
            db.chat_historyQueries.selectByTimeRange(
                connectionId = connectionId,
                fromTime = fromTime,
                toTime = toTime,
            ).executeAsList()
        }

    /** 删除某个连接的全部对话（数据清理用；业务写入路径从不删除）。返回删除行数。 */
    suspend fun deleteByConnectionId(connectionId: String): Long = withDatabase { db ->
        // DELETE 型语句生成 QueryResult<Long>（受影响行数），不是行查询。
        db.chat_historyQueries.deleteByConnectionId(connectionId).value
    }

    /** 清理 [cutoffTime]（Unix 毫秒）之前的全部对话。返回删除行数。 */
    suspend fun cleanOldBefore(cutoffTime: Long): Long = withDatabase { db ->
        db.chat_historyQueries.cleanOld(cutoffTime).value
    }

    private suspend fun <T> withDatabase(block: (AiChatDatabase) -> T): T =
        DatabaseManager.withDriver { driver ->
            block(AiChatDatabase(driver))
        }
}
