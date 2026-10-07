package com.mqclient.core.db

import com.mqclient.core.database.DatabaseManager

/**
 * mq_ai_conversation 表（MQ 对象 AI 分析问答）的写入口（本次只做写入，无查询方法——
 * 查询随查询 UI 一起来）。
 *
 * 所有访问都经 [DatabaseManager.withDriver] 跑在单槽串行调度器上（单条 JDBC 连接不支持
 * 并发，见 DatabaseManager 的线程模型注释）。错误处理交给调用方（事件 handler），这里
 * 只做纯写入。
 *
 * 建表是惰性的：首次访问时 `CREATE TABLE IF NOT EXISTS` 一次（幂等）。handler 只在
 * 事件到达时才摸库，不依赖 DatabaseManager 的初始化顺序（与 mq_command / mq_connection
 * 同一模式）。
 *
 * 命名说明：SQLDelight 把蛇形表名 `mq_ai_conversation` 的 queries 属性生成为
 * `mq_ai_conversationQueries`；对外类型统一是 [MqAiConversationRecord]，生成物不出本文件。
 */
object MqAiConversationRepository {

    /** 插入一条分析问答记录。字段由发出方填好（事件自包含），这里忠实落库。 */
    suspend fun save(record: MqAiConversationRecord): Unit = withDb { db ->
        db.mq_ai_conversationQueries.insertConversation(
            id = record.id,
            connection_id = record.connectionId,
            object_type = record.objectType,
            object_name = record.objectName,
            question = record.question,
            context_json = record.contextJson,
            answer = record.answer,
            status = record.status,
            error_message = record.errorMessage,
            request_time = record.requestTime,
            response_time = record.responseTime,
            duration_ms = record.durationMs,
        )
    }

    @Volatile
    private var schemaReady = false

    private suspend fun <T> withDb(block: (CoreDatabase) -> T): T =
        DatabaseManager.withDriver { driver ->
            if (!schemaReady) {
                CoreDatabase.Schema.create(driver)
                schemaReady = true
            }
            block(CoreDatabase(driver))
        }
}
