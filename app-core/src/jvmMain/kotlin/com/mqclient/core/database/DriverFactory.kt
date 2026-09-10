package com.mqclient.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.mqclient.core.ConfigManager
import java.io.File
import java.io.IOException

/**
 * JVM（桌面）驱动工厂：JDBC SQLite（org.xerial sqlite-jdbc）。
 *
 * 数据库路径：`~/.mqclient/mqclient.db` —— 和 `config.json` 同一个目录，因此也尊重
 * `mqclient.config.dir` 覆盖（测试与便携模式不会写到用户真实的 `~/.mqclient` 去）。
 * 注意：此处只打开数据库并做连接级优化，不执行任何建表语句 ——
 * 表由各业务模块自行管理（CREATE TABLE IF NOT EXISTS + 模块前缀）。
 */
actual class DriverFactory {

    actual fun createDriver(): SqlDriver {
        // 1. 确保目录存在（建不出来就明说 —— 之前忽略返回值，后面会以一个更难懂的错误失败）
        val dbDir = ConfigManager.configDir().toNioPath().toFile()
        if (!dbDir.exists() && !dbDir.mkdirs()) {
            throw IOException("无法创建数据库目录: ${dbDir.absolutePath}")
        }
        val dbPath = File(dbDir, DB_FILE_NAME).absolutePath

        // 2. 创建驱动
        val driver = JdbcSqliteDriver("jdbc:sqlite:$dbPath")

        // 3. 执行优化配置。WAL 写进库文件本身、是持久的；其余都是连接级 PRAGMA，
        //    每次建连接都要重放一遍（重复执行幂等）。读一下 .value，确保语句真的跑了。
        driver.execute(null, "PRAGMA journal_mode=WAL", 0).value
        driver.execute(null, "PRAGMA synchronous=NORMAL", 0).value
        driver.execute(null, "PRAGMA cache_size=20000", 0).value
        // 写锁被别的连接 / 进程占着时等 5 秒再报 SQLITE_BUSY，而不是立刻失败。
        driver.execute(null, "PRAGMA busy_timeout=5000", 0).value
        driver.execute(null, "PRAGMA foreign_keys=ON", 0).value

        return driver
    }

    private companion object {
        const val DB_FILE_NAME = "mqclient.db"
    }
}
