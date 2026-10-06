package com.mqclient.core.db

import com.mqclient.core.database.DatabaseManager

/**
 * mq_status 状态采集表的写入口（本次只做写入与清理，查询方法随查询 UI 再补）。
 *
 * 所有访问都经 [DatabaseManager.withDriver] 跑在单槽串行调度器上（单条 JDBC 连接
 * 不支持并发，见 DatabaseManager 的线程模型注释）。错误处理交给调用方（事件 handler /
 * 清理任务），这里只做纯读写。
 *
 * 建表是惰性的：首次访问时 `CREATE TABLE IF NOT EXISTS` 一次（幂等）。handler 只在
 * 事件到达时才摸库，不依赖 DatabaseManager 的初始化顺序（与 mq_connection 同一模式）。
 *
 * 命名说明：SQLDelight 把蛇形表名 `mq_status` 的 queries 属性生成为
 * `mq_statusQueries`；对外类型统一是 [MqStatusRecord]，生成物不出本文件。
 */
object MqStatusHistoryRepository {

    /** 单批删除上限：单事务别吞整表，[deleteOlderThan] 循环调用直到删完。 */
    private const val DELETE_BATCH_SIZE = 10_000L

    /**
     * 批量插入一次扫描的采集记录：**一批一个事务**（原子 + 免去逐条 autocommit 的
     * 开销；心跳 10 秒一批几十条，远在 SQLite 舒适区内）。
     */
    suspend fun saveBatch(records: List<MqStatusRecord>): Unit = withDb { db ->
        db.transaction {
            for (record in records) {
                db.mq_statusQueries.insertStatus(
                    id = record.id,
                    connection_id = record.connectionId,
                    object_type = record.objectType,
                    object_name = record.objectName,
                    property_name = record.propertyName,
                    property_value = record.propertyValue,
                    numeric_value = record.numericValue,
                    create_time = record.createTime,
                )
            }
        }
    }

    /**
     * 删除 create_time 早于 [cutoff]（Unix 毫秒）的全部记录：按 [DELETE_BATCH_SIZE]
     * 分批循环直到删完（单事务删百万行会长时间占住写锁）。返回删除总行数。
     */
    suspend fun deleteOlderThan(cutoff: Long): Long {
        var total = 0L
        while (true) {
            val deleted = withDb { db ->
                db.mq_statusQueries.deleteOlderThanBatch(cutoff).value
            }
            total += deleted
            if (deleted < DELETE_BATCH_SIZE) break
        }
        return total
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
