package com.mqclient.core.db

import com.mqclient.core.database.DatabaseManager
import com.mqclient.core.event.systemTimeMillis

/**
 * mq_connection 快照表的读写入口。
 *
 * 所有访问都经 [DatabaseManager.withDriver] 跑在单槽串行调度器上 —— 单条 JDBC
 * 连接不支持并发，见 DatabaseManager 的线程模型注释（不需要再包 Dispatchers.IO，
 * withDriver 内部已经把 block 派到 dbContext）。错误处理交给调用方（事件 handler /
 * UI），这里只做纯读写。
 *
 * 建表是惰性的：首次访问时 `CREATE TABLE IF NOT EXISTS` 一次（幂等）。不放在
 * CoreModule.initialize 里做 —— 那里 DatabaseManager 还没初始化（接线点在 app-ui
 * 的 Main.kt，顺序是先建总线再建库）， handler 也只在该有事件时才摸库。
 *
 * 命名说明：SQLDelight 把蛇形表名 `mq_connection` 的 queries 属性生成为
 * `mq_connectionQueries`（数据类是 `Mq_connection`），下面的名字以生成物为准；
 * 对外类型统一映射成 [MqConnectionSnapshot]，生成物不出本文件。
 */
object MqConnectionSnapshotRepository {

    /**
     * 插入一条快照。connection_id 与行一一对应（每次保存连接都换发新 id，见
     * ConnectionStore.update），不更新旧行；[MqConnectionSnapshot.id] 被忽略（表自增），
     * [createdTime] 由调用方给（事件 handler 用当前时刻）。
     */
    suspend fun save(snapshot: MqConnectionSnapshot): Unit = withDb { db ->
        db.mq_connectionQueries.insert(
            connection_id = snapshot.connectionId,
            type = snapshot.type,
            name = snapshot.name,
            host = snapshot.host,
            port = snapshot.port?.toLong(),
            target = snapshot.target,
            user_name = snapshot.userName,
            created_time = snapshot.createdTime,
        )
    }

    /** 某个连接的全部快照（按时间倒序，即新→旧的历史链）。 */
    suspend fun getAllByConnectionId(connectionId: String): List<MqConnectionSnapshot> = withDb { db ->
        db.mq_connectionQueries.selectByConnectionId(connectionId).executeAsList().map { it.toModel() }
    }

    /** 某个连接的最新一条快照（没有则 null）。 */
    suspend fun getLatestByConnectionId(connectionId: String): MqConnectionSnapshot? = withDb { db ->
        db.mq_connectionQueries.selectLatestByConnectionId(connectionId).executeAsOneOrNull()?.toModel()
    }

    /** 按自增 id 精确取一条（operation_history 已落库的快照 id 反查）。 */
    suspend fun getById(id: Long): MqConnectionSnapshot? = withDb { db ->
        db.mq_connectionQueries.selectById(id).executeAsOneOrNull()?.toModel()
    }

    /** 全部快照（按时间倒序）；调试 / 运维展示用，量大了再加 limit。 */
    suspend fun getAll(): List<MqConnectionSnapshot> = withDb { db ->
        db.mq_connectionQueries.selectAll().executeAsList().map { it.toModel() }
    }

    /** 按连接清掉全部快照。仅限清理场景（表本身不做业务删除）。 */
    suspend fun deleteByConnectionId(connectionId: String): Unit = withDb { db ->
        db.mq_connectionQueries.deleteByConnectionId(connectionId)
    }

    /** 当前时刻（commonMain 没有 System.currentTimeMillis，走 app-core 唯一的墙上时钟）。 */
    fun now(): Long = systemTimeMillis()

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

    private fun Mq_connection.toModel(): MqConnectionSnapshot = MqConnectionSnapshot(
        id = id,
        connectionId = connection_id,
        type = type,
        name = name,
        host = host,
        port = port?.toInt(),
        target = target,
        userName = user_name,
        createdTime = created_time,
    )
}
