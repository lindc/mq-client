package com.mqclient.core.database

import app.cash.sqldelight.db.SqlDriver

/**
 * 平台抽象：创建 SQLite [SqlDriver]（数据库文件 `~/.mqclient/mqclient.db`）。
 *
 * app-core 只负责驱动本身，**不创建任何表** —— 各业务模块拿到 driver 后
 * 用各自 sqldelight 生成的 Database 管理自己的表（共享同一文件）。
 */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}
