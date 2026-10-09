package com.mqclient.aiservice.db

import com.mqclient.core.database.DatabaseManager

/**
 * ai_connection 快照表的读写入口。
 *
 * 所有访问都经 [DatabaseManager.withDriver] 跑在单槽串行调度器上 —— 单条 JDBC
 * 连接不支持并发，见 DatabaseManager 的线程模型注释。错误处理交给调用方
 * （事件 handler / UI），这里只做纯读写。
 *
 * 命名说明：SQLDelight 生成的 queries 属性是 `ai_connectionQueries`（数据类是
 * `Ai_connection`，属性名与 snake_case 列名一致），下面的名字以生成物为准。
 */
object AiConnectionSnapshotRepository {

    /**
     * 插入一条快照。prevId 由事件 payload 提供（url 变化 = 旧连接的 UUID；全新建
     * 为 null），Repository 不做计算 —— 跨连接的追溯关系只有发出方知道。
     */
    suspend fun saveSnapshot(
        connectionId: String,
        prevId: String?,
        serviceUrl: String,
        createdTime: Long = System.currentTimeMillis(),
    ): Unit = withDatabase { db ->
        db.ai_connectionQueries.insertSnapshot(
            connection_id = connectionId,
            prev_id = prevId,
            service_url = serviceUrl,
            created_time = createdTime,
        )
    }

    /** 某个连接的最新一条快照（没有则 null）。 */
    suspend fun getLatestSnapshot(connectionId: String): Ai_connection? = withDatabase { db ->
        db.ai_connectionQueries.selectLatestByConnectionId(connectionId).executeAsOneOrNull()
    }

    /** 某个连接的全部快照（按 id 升序，即快照链顺序）。 */
    suspend fun getAllSnapshots(connectionId: String): List<Ai_connection> = withDatabase { db ->
        db.ai_connectionQueries.selectAllByConnectionId(connectionId).executeAsList()
    }

    private suspend fun <T> withDatabase(block: (AiChatDatabase) -> T): T =
        DatabaseManager.withDriver { driver ->
            block(AiChatDatabase(driver))
        }
}
