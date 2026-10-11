package com.mqclient.core.database

import app.cash.sqldelight.db.SqlDriver
import com.mqclient.core.event.systemTimeMillis
import com.mqclient.core.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** 数据库层的日志 tag。 */
private const val LOG_TAG = "core.db"

/**
 * SQLite 基础设施唯一入口（app-core 只提供基础设施，不定义任何表）：
 *
 * 1. 启动早期调用 [initialize]（一次即可，幂等；接线点在 app-ui 的 `Main.kt`）；
 * 2. 各业务模块通过 [withDriver] 在共享的 [SqlDriver] 上执行查询（用自己模块内 sqldelight
 *    生成的 Database 包装 driver、建自己的表）——驱动没有第二个出口，生命周期不可能
 *    脱离本对象的管理；
 * 3. **所有对驱动的访问都必须跑在 [dbContext] 上**（见下面的线程模型）。
 *
 * 所有模块共享同一个数据库文件（`~/.mqclient/mqclient.db`，见 [DriverFactory]），
 * 因此表名应使用模块前缀避免冲突；app-core 不负责任何表结构。
 *
 * ### 线程模型：单连接 = 必须串行
 *
 * `JdbcSqliteDriver` 内部就是一条 JDBC `Connection`，而 JDBC 连接**不支持并发多线程
 * 访问**（结果是 SQLException 或数据错乱）。[dbContext] 是一个「同时只跑一个任务」的
 * 调度器（`Dispatchers.IO.limitedParallelism(1)`，由 [initialize] 注入 —— commonMain 里
 * 拿不到 `Dispatchers.IO`），于是：
 *
 * - [withDriver] 的 block 是**非挂起**的（`(SqlDriver) -> T`）：调用方在类型上就写不出
 *   挂起调用，槽位不会被中途让出去，这是这套方案能成立的关键；
 * - SQLDelight 的协程扩展（`Query.asFlow(dbContext)`、`transactionWithContext(dbContext)`）
 *   收的正是 [dbContext]，天然被同一个槽位串行化；
 * - **需要跨多个挂起调用保持事务时，用 `transactionWithContext(dbContext)`，不要自己包
 *   [withDriver]**：`limitedParallelism` 的许可在任务返回时就释放了，块里一挂起就等于
 *   放行下一个任务，连接会被并发持有。
 *
 * 为什么不上连接池：本地桌面 app、单库单文件，串行化的吞吐足够；加 HikariCP 那类依赖
 * 换来的是这里根本用不到的并发能力。真需要并发读写时再回到这个话题。
 */
object DatabaseManager {

    private const val NOT_INITIALIZED = "Database not initialized. Call DatabaseManager.initialize() first."

    /**
     * 驱动与调度器成对出现：要么都有、要么都没有。
     *
     * `@Volatile` + 双检锁：旧写法（裸 `var` + check-then-set）在多线程首次访问时会建出
     * 两条连接，其中一条永远泄漏。
     */
    @Volatile
    private var _driver: SqlDriver? = null

    /**
     * 串行化 DB 访问的调度器。
     *
     * 刻意只持 dispatcher、**不持 CoroutineScope**：把带 Job 的 context 交给 `withContext`
     * 会替换掉调用方的 Job（context 合并时 Job 元素优先），结果是调用方自己的作用域取消
     * 再也中断不了这次查询，而这里的取消反而会提前终结调用方的续体。只传 dispatcher
     * 才不会破坏结构化并发。
     */
    @Volatile
    private var _dbContext: CoroutineDispatcher? = null

    /**
     * 所有 DB 访问要跑的调度器（SQLDelight 的 `asFlow` / `transactionWithContext` 直接
     * 把它当 `CoroutineContext` 收）。
     *
     * @throws IllegalStateException 若 [initialize] 尚未调用
     */
    val dbContext: CoroutineDispatcher
        get() = _dbContext ?: throw IllegalStateException(NOT_INITIALIZED)

    /**
     * 创建并持有平台 SQLite 驱动。重复调用是安全的（仅首次生效，不会换掉已有的连接）。
     *
     * [dbDispatcher] 应当是**单槽**调度器（`Dispatchers.IO.limitedParallelism(1)`），
     * 理由见类注释；测试里可以传自己的调度器。
     */
    fun initialize(driverFactory: DriverFactory, dbDispatcher: CoroutineDispatcher) {
        if (_driver != null) {
            Log.debug(LOG_TAG, "db initialize ignored (already initialized)")
            return
        }
        synchronized(this) {
            if (_driver == null) {
                try {
                    _driver = driverFactory.createDriver()
                    _dbContext = dbDispatcher
                    Log.info(LOG_TAG, "database initialized: dispatcher=$dbDispatcher")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.error(
                        LOG_TAG,
                        "database initialize failed: error=${e.message ?: e::class.simpleName}",
                    )
                    throw e
                }
            } else {
                Log.debug(LOG_TAG, "db initialize ignored (already initialized, inside lock)")
            }
        }
    }

    /**
     * 在串行化 DB 调度器上执行 [block]，保证单连接不被并发访问。
     *
     * [block] 非挂起（类型 + 编译器双保险）：需要跨挂起保持事务，请用
     * `transactionWithContext(dbContext)`。
     *
     * 日志：正常路径一行 DEBUG（耗时），失败一行 ERROR 后原样上抛 —— DB 访问失败
     * 不能只靠调用方上报，日志里必须有迹可查。
     *
     * @throws IllegalStateException 若 [initialize] 尚未调用
     */
    suspend fun <T> withDriver(block: (SqlDriver) -> T): T {
        val driver = _driver ?: throw IllegalStateException(NOT_INITIALIZED)
        val start = systemTimeMillis()
        try {
            val result = withContext(dbContext) { block(driver) }
            Log.debug(LOG_TAG, "db op ok: duration=${systemTimeMillis() - start}ms")
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.error(
                LOG_TAG,
                "db op failed: error=${e.message ?: e::class.simpleName} " +
                    "duration=${systemTimeMillis() - start}ms",
            )
            throw e
        }
    }

    /**
     * 关闭共享连接（应用退出时调；重复调用安全）。
     *
     * ⚠️ 调用点必须在「不再有人发起 DB 访问」之后，退出三步的顺序是：
     * `ModuleRegistry.disposeServices()`（先停模块资源）→ `CoreModule.shutdown()`
     * （再停调度器与事件订阅）→ `DatabaseManager.close()`（最后关库，就是这里）。
     * 提前关库的话，关闭途中还在跑的查询会打在已关的连接上（那条查询会抛异常，
     * 不影响库本身）。
     */
    fun close() {
        synchronized(this) {
            val driver = _driver ?: return
            _driver = null
            _dbContext = null
            try {
                driver.close()
                Log.info(LOG_TAG, "database closed")
            } catch (e: Throwable) {
                // 语义不变（仍上抛给退出流程），只是先留痕。
                Log.error(LOG_TAG, "database close failed: error=${e.message ?: e::class.simpleName}")
                throw e
            }
        }
    }
}
