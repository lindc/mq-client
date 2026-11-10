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

    /** 物理连接是否仍可用（半开 / 已关闭时 false）。 */
    fun isUsable(): Boolean

    /** 关闭连接；必须幂等、自行兜异常。 */
    suspend fun close()
}

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
