package com.mqclient.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
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
 * 建表是惰性的：首次访问时 `CREATE TABLE IF NOT EXISTS` 一次（幂等），随后做一次
 * 旧结构迁移（见 [migrateLegacySchemaIfNeeded]）。不放在 CoreModule.initialize 里做
 * —— 那里 DatabaseManager 还没初始化（接线点在 app-ui 的 Main.kt，顺序是先建总线
 * 再建库），handler 也只在该有事件时才摸库。
 *
 * 表是追加式的：只有 INSERT 和 SELECT，没有 UPDATE / DELETE。每条记录的 [MqConnectionSnapshot.id]
 * 就是连接 UUIDv7（一 id 一行，编辑连接换发新 id，旧行保留）。
 *
 * 命名说明：SQLDelight 把蛇形表名 `mq_connection` 的 queries 属性生成为
 * `mq_connectionQueries`（数据类是 `Mq_connection`），下面的名字以生成物为准；
 * 对外类型统一映射成 [MqConnectionSnapshot]，生成物不出本文件。
 */
object MqConnectionSnapshotRepository {

    /**
     * 插入一条快照。[MqConnectionSnapshot.id] 由调用方给（事件 payload 里的连接
     * UUIDv7），[createdAt] 同理（事件 handler 用当前时刻）。
     */
    suspend fun save(snapshot: MqConnectionSnapshot): Unit = withDb { db ->
        db.mq_connectionQueries.insertConnection(
            id = snapshot.id,
            name = snapshot.name,
            type = snapshot.type,
            host = snapshot.host,
            port = snapshot.port.toLong(),
            target = snapshot.target,
            user_name = snapshot.userName,
            created_at = snapshot.createdAt,
        )
    }

    /** 全部快照（按创建时间倒序）；调试 / 运维展示用，量大了再加 limit。 */
    suspend fun getAll(): List<MqConnectionSnapshot> = withDb { db ->
        db.mq_connectionQueries.selectAll().executeAsList().map { it.toModel() }
    }

    /** 当前时刻（commonMain 没有 System.currentTimeMillis，走 app-core 唯一的墙上时钟）。 */
    fun now(): Long = systemTimeMillis()

    @Volatile
    private var schemaReady = false

    private suspend fun <T> withDb(block: (CoreDatabase) -> T): T =
        DatabaseManager.withDriver { driver ->
            if (!schemaReady) {
                migrateLegacySchemaIfNeeded(driver)
                CoreDatabase.Schema.create(driver)
                schemaReady = true
            }
            block(CoreDatabase(driver))
        }

    /**
     * 一次性迁移：旧表结构（`id INTEGER` 自增主键 + `connection_id TEXT` 列）→
     * 新结构（`id TEXT` 主键 = 连接 UUIDv7，列名 created_at）。历史数据整表平移：
     * connection_id → id、created_time → created_at，可空的 name/host/port 用
     * COALESCE 落到 NOT NULL 默认值。
     *
     * 探测旧结构看 `connection_id` 列是否存在 —— 新结构没有这一列，所以天然幂等
     * （迁移过 / 全新库都直接跳过）。迁移必须在 [CoreDatabase.Schema.create] 之前做：
     * 旧表占着 mq_connection 名字，`IF NOT EXISTS` 的建表语句会被它挡掉。
     *
     * ## 为什么必须在一个事务里
     *
     * 四步（RENAME → CREATE → INSERT…SELECT → DROP）旧实现是裸 `driver.execute`，
     * 任意一步崩溃（掉电 / 磁盘满 / 进程被杀）都会留下半完成状态：`mq_connection`
     * 不存在而 `mq_connection_legacy` 变成孤儿。下次启动探测 `connection_id` 列返回
     * false，迁移被**永久跳过**，`Schema.create` 建出一张空表 —— 用户的历史快照
     * 就无声地没了。
     *
     * SQLite 的 DDL 是事务性的（只要没有中途 commit），所以整段包进一个事务就够了。
     */
    private fun migrateLegacySchemaIfNeeded(driver: SqlDriver) {
        val hasLegacyColumn = driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM pragma_table_info('mq_connection') WHERE name = 'connection_id'",
            { cursor ->
                cursor.next().value
                QueryResult.Value((cursor.getLong(0) ?: 0L) > 0L)
            },
            0,
        ).value
        if (!hasLegacyColumn) return

        // 用 Transacter.transaction：它会把驱动的事务上下文设好，块内的 driver.execute
        // 都隶属同一个事务；抛异常自动回滚（`newTransaction()` + endTransaction 是
        // protected 的，不能用）。
        CoreDatabase(driver).transaction {
            driver.execute(null, "ALTER TABLE mq_connection RENAME TO mq_connection_legacy", 0)
            CoreDatabase.Schema.create(driver)
            driver.execute(
                null,
                """
                INSERT INTO mq_connection(id, name, type, host, port, target, user_name, created_at)
                SELECT connection_id, COALESCE(name, ''), type, COALESCE(host, ''),
                       COALESCE(port, 0), target, user_name, created_time
                FROM mq_connection_legacy
                """.trimIndent(),
                0,
            )
            driver.execute(null, "DROP TABLE mq_connection_legacy", 0)
        }
        // 旧表索引随 DROP 消失；新索引已由上面的 Schema.create 建好。
    }

    private fun Mq_connection.toModel(): MqConnectionSnapshot = MqConnectionSnapshot(
        id = id,
        type = type,
        name = name,
        host = host,
        port = port.toInt(),
        target = target,
        userName = user_name,
        createdAt = created_at,
    )
}
