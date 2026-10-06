package com.mqclient.core.db

import com.mqclient.core.database.DatabaseManager

/**
 * mq_command 写操作命令历史表的写入口（本次只做写入，无查询方法——查询随查询 UI 一起来）。
 *
 * 所有访问都经 [DatabaseManager.withDriver] 跑在单槽串行调度器上（单条 JDBC 连接不支持
 * 并发，见 DatabaseManager 的线程模型注释）。错误处理交给调用方（事件 handler），这里
 * 只做纯写入。
 *
 * 建表是惰性的：首次访问时 `CREATE TABLE IF NOT EXISTS` 一次（幂等）。handler 只在
 * 事件到达时才摸库，不依赖 DatabaseManager 的初始化顺序（与 mq_connection 同一模式）。
 *
 * 命名说明：SQLDelight 把蛇形表名 `mq_command` 的 queries 属性生成为
 * `mq_commandQueries`；对外类型统一是 [MqCommandRecord]，生成物不出本文件。
 */
object MqCommandHistoryRepository {

    /** 插入一条写操作命令记录。字段由发出方填好（事件自包含），这里忠实落库。 */
    suspend fun save(record: MqCommandRecord): Unit = withDb { db ->
        db.mq_commandQueries.insertCommand(
            id = record.id,
            connection_id = record.connectionId,
            command = record.command,
            response = record.response,
            status = record.status,
            reason_code = record.reasonCode,
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
