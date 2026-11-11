package com.mqclient.starrocks.jdbc

/**
 * JDBC 连接器抽象（**接口隔离**的关键件）。
 *
 * `StarRocksConnectionManager` 只依赖本接口，不直接 `DriverManager.getConnection`：
 * 真实实现 [MysqlJdbcConnector] 在 jvmMain 适配 `java.sql.Connection`，
 * 测试在 commonTest 用 `FakeJdbcConnector` 注入，两者互不影响。
 *
 * 为什么 `connect` 返回 [JdbcConnection] 而不是 `java.sql.Connection`：
 * `java.sql` 是 JVM 专属，commonMain / commonTest 编译期看不到；用一个平台
 * 中立的句柄接口做适配，才能让协议无关的模板层与单测都留在 common 源集
 * （这也是「接口隔离」比直接用 JDBC 类型更强的一层）。
 */
interface JdbcConnector {

    /**
     * 建立一条物理连接（阻塞 IO 由实现方切到 Dispatchers.IO）。
     *
     * @param url JDBC URL（如 `jdbc:mysql://host:9030/`）。
     * @param user 用户名。
     * @param password 密码。
     * @throws JdbcFailure 底层 SQLException 被翻译成平台中立异常后抛出。
     * @throws JdbcConnectTimeoutException 连接 / 读超时。
     */
    suspend fun connect(url: String, user: String, password: String): JdbcConnection

    /** 释放连接器自身的资源（驱动级；每条连接的关闭在 [JdbcConnection.close]）。 */
    suspend fun close()
}

/**
 * 一条 JDBC 连接的平台中立句柄（**轻量句柄，即用即毁**）。
 *
 * 对齐仓库「底层连接共享，业务句柄用完即毁」的规范：管理器持有的是
 * [JdbcConnector]（重量级），[JdbcConnection] 是每次 connect 的产物，
 * 用完必须在 finally 中 [close]。
 */
interface JdbcConnection {

    /** 执行探针语句（`SELECT 1`），返回结果集是否有行。 */
    suspend fun probe(): Boolean

    /**
     * 执行一条 SQL，按 [batchSize] 行为单位把结果流式回调给 [onBatch]。
     *
     * 平台中立的流式取数：SELECT 流按行分批（末批 [JdbcQueryBatch.isLast] = true），
     * 非查询语句回调**单个**带 [JdbcQueryBatch.updateCount] 的批次。实现负责把
     * `SQLException` 翻译成 [JdbcFailure] / [JdbcConnectTimeoutException]。
     *
     * 注意契约：[onBatch] 总在**调用方协程上下文**回调（实现内部做 IO 切换但不
     * 把回调切到 IO 线程）—— 调用方在 `flow {}` 里 emit 依赖这一点（Flow 上下文
     * 不变量）。
     */
    suspend fun executeQuery(
        sql: String,
        batchSize: Int = DEFAULT_QUERY_BATCH_SIZE,
        onBatch: suspend (JdbcQueryBatch) -> Unit,
    )

    /** 物理连接是否仍可用（半开 / 已关闭时 false）。 */
    fun isUsable(): Boolean

    /** 关闭连接；必须幂等、自行兜异常。 */
    suspend fun close()

    companion object {
        /** 查询分批的默认行数（UI 渲染与内存占用的折中）。 */
        const val DEFAULT_QUERY_BATCH_SIZE: Int = 100
    }
}

/**
 * 一次 JDBC 查询的平台中立结果批次（对标 olap-base 的 `ResultBatch`，但**不依赖
 * olap-base** —— jdbc 适配层保持在协议最底层）。
 *
 * @param columns 列名（每个批次都带全量 schema）；非查询语句为空。
 * @param rows 行数据（原生值已映射成 Kotlin 基本类型 / null）。
 * @param updateCount 非查询语句的受影响行数；SELECT 流为 null。
 * @param isLast 是否末批。
 */
class JdbcQueryBatch(
    val columns: List<String> = emptyList(),
    val rows: List<List<Any?>> = emptyList(),
    val updateCount: Long? = null,
    val isLast: Boolean,
)

/**
 * 平台中立的 JDBC 失败（由 jvmMain 的 `SQLException` 翻译而来）。
 *
 * [sqlState] / [errorCode] 是错误映射的输入：`StarRocksSqlDialect` 按它们
 * 分类成 `OlapErrorCode`，UI 侧再按 `errorKey` 翻译成用户友好文案。
 */
class JdbcFailure(
    message: String?,
    val sqlState: String?,
    val errorCode: Int,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 连接 / 读超时（与 [JdbcFailure] 分开，便于精确映射到 `OlapErrorCode.TIMEOUT`）。 */
class JdbcConnectTimeoutException(message: String) : Exception(message)
