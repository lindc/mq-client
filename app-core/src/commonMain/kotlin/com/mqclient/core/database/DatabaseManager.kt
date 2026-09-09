package com.mqclient.core.database

import app.cash.sqldelight.db.SqlDriver

/**
 * SQLite 基础设施唯一入口（app-core 只提供基础设施，不定义任何表）：
 *
 * 1. 启动早期调用 [initialize]（一次即可，幂等）；
 * 2. 各业务模块通过 [getDriver] 拿到共享的 [SqlDriver]，用自己模块内
 *    sqldelight 生成的 Database 包装它，自行管理自己的表。
 *
 * 所有模块共享同一个数据库文件（~/.mqclient/mqclient.db），因此表名应
 * 使用模块前缀避免冲突；app-core 不负责任何表结构。
 */
object DatabaseManager {

    private var _driver: SqlDriver? = null

    /** 创建并持有平台 SQLite 驱动。重复调用是安全的（仅首次生效）。 */
    fun initialize(driverFactory: DriverFactory) {
        if (_driver == null) {
            _driver = driverFactory.createDriver()
        }
    }

    /**
     * 返回共享的 [SqlDriver]。
     *
     * @throws IllegalStateException 若 [initialize] 尚未调用
     */
    fun getDriver(): SqlDriver {
        return _driver ?: throw IllegalStateException("Database not initialized. Call DatabaseManager.initialize() first.")
    }
}
