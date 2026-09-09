package com.mqclient.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

/**
 * JVM（桌面）驱动工厂：JDBC SQLite（org.xerial sqlite-jdbc）。
 *
 * 数据库路径：`~/.mqclient/mqclient.db`。
 * 注意：此处只打开数据库并做连接级优化，不执行任何建表语句 ——
 * 表由各业务模块自行管理（CREATE TABLE IF NOT EXISTS + 模块前缀）。
 */
actual class DriverFactory {

    actual fun createDriver(): SqlDriver {
        // 1. 确保目录存在
        val homeDir = System.getProperty("user.home")
        val dbDir = File(homeDir, ".mqclient")
        if (!dbDir.exists()) {
            dbDir.mkdirs()
        }
        val dbPath = File(dbDir, "mqclient.db").absolutePath

        // 2. 创建驱动
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")

        // 3. 执行优化配置（WAL 模式持久化于库文件；其余为连接级，重复执行幂等）
        driver.execute(null, "PRAGMA journal_mode=WAL", 0)
        driver.execute(null, "PRAGMA synchronous=NORMAL", 0)
        driver.execute(null, "PRAGMA cache_size=20000", 0)

        return driver
    }
}
