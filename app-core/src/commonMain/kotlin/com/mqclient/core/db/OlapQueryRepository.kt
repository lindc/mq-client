package com.mqclient.core.db

import app.cash.sqldelight.db.SqlDriver
import com.mqclient.core.database.DatabaseManager
import com.mqclient.core.event.systemTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `olap_query_history` / `olap_draft` 两表的读写入口（与 [MqCommandHistoryRepository]
 * 同款：共享 `CoreDatabase` 单库、惰性建表、所有访问经 [DatabaseManager.withDriver]
 * 的单槽串行调度器）。
 *
 * 公开的 suspend 方法都先切 [Dispatchers.IO]：落库是磁盘 IO，不能压在调用方
 * （UI / Flow 收集）的调度器上；[DatabaseManager.withDriver] 内部再切它自己的
 * 单线程 `dbContext`，两层各司其职，不破坏「单条 JDBC 连接不支持并发」的约束。
 *
 * 建表惰性：首次访问时 `CoreDatabase.Schema.create(driver)` 一次（幂等），
 * 不依赖 DatabaseManager 的初始化顺序。
 */
object OlapQueryRepository {

    /** 追加一条执行记录（成功 / 失败都写；绝不写结果集）。 */
    suspend fun save(record: OlapQueryRecord): Unit = withContext(Dispatchers.IO) {
        withDb { db ->
            db.olap_queryQueries.insertHistory(
                id = record.id,
                connection_id = record.connectionId,
                sql_text = record.sqlText,
                status = record.status,
                duration_ms = record.durationMs,
                error_message = record.errorMessage,
                created_at = record.createdAt,
            )
        }
    }

    /** 最近 [limit] 条执行历史（默认 50，历史面板用）。 */
    suspend fun recent(connectionId: String, limit: Long = 50): List<OlapQueryRecord> =
        withContext(Dispatchers.IO) {
            withDb { db ->
                db.olap_queryQueries
                    .recentByConnection(connectionId, limit)
                    .executeAsList()
                    .map { row ->
                        OlapQueryRecord(
                            id = row.id,
                            connectionId = row.connection_id,
                            sqlText = row.sql_text,
                            status = row.status,
                            durationMs = row.duration_ms,
                            errorMessage = row.error_message,
                            createdAt = row.created_at,
                        )
                    }
            }
        }

    /** 覆盖写当前连接的 SQL 草稿（编辑器防抖后调用）。 */
    suspend fun saveDraft(connectionId: String, sqlText: String): Unit = withContext(Dispatchers.IO) {
        withDb { db ->
            db.olap_queryQueries.upsertDraft(
                connection_id = connectionId,
                sql_text = sqlText,
                updated_at = systemTimeMillis(),
            )
        }
    }

    /** 读取上次草稿；没有记录返回 null。 */
    suspend fun loadDraft(connectionId: String): String? = withContext(Dispatchers.IO) {
        withDb { db ->
            db.olap_queryQueries.selectDraft(connectionId).executeAsOneOrNull()
        }
    }

    /**
     * 记住**已经在哪个 driver 上建过表**（而非一个布尔）：换了 driver（测试换临时库、
     * 运行时重新初始化 DB）必须重新 CREATE，否则空库上直接查表会失败。
     * `Schema.create` 全是 `CREATE TABLE IF NOT EXISTS`，同一 driver 重复建幂等。
     */
    @Volatile
    private var schemaDriver: SqlDriver? = null

    private suspend fun <T> withDb(block: (CoreDatabase) -> T): T =
        DatabaseManager.withDriver { driver ->
            if (schemaDriver !== driver) {
                CoreDatabase.Schema.create(driver)
                schemaDriver = driver
            }
            block(CoreDatabase(driver))
        }
}
