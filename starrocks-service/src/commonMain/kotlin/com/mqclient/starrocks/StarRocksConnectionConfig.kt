package com.mqclient.starrocks

import com.mqclient.olap.base.connection.AnalyticsConnectionConfig

/**
 * StarRocks 连接配置快照（不可变；实现 olap-base 的 [AnalyticsConnectionConfig]）。
 *
 * S2 起字段改为**具名类型字段**（host / port / user / password / database），
 * 表单直接读写它们；[options] 由字段派生，保持与 app-core `ConnectionInfo.options`
 * 同形 —— 落盘时密码等敏感项由 `SensitiveOptionsSerializer` 加密，本类不感知。
 *
 * [jdbcUrl] 默认按 `jdbc:mysql://<host>:<port>/<database>` 拼（StarRocks FE 的
 * MySQL 协议端口 9030）；[jdbcUrlOverride] 供真机验证带连接参数
 * （connectTimeout / socketTimeout / connectionTimeZone …）时使用，非空时优先。
 *
 * 改配置 = 断开重连（与 MQ 系 `connection.updated` 同口径），运行期不改写。
 */
data class StarRocksConnectionConfig(
    override val connectionId: String,
    override val displayName: String,
    /** FE 主机名 / IP。 */
    val host: String = DEFAULT_HOST,
    /** FE query port（MySQL 协议）。 */
    val port: Int = DEFAULT_PORT,
    /** 用户名。 */
    val user: String = DEFAULT_USER,
    /** 密码（明文只在进程内；落盘由 app-core 加密）。 */
    val password: String = "",
    /** 默认库（`SELECT 1` 不需要，但连接 URL 需要一个存在的库）。 */
    val database: String = DEFAULT_DATABASE,
    /** 完整 JDBC URL 覆盖（高级：带连接参数）；空串 = 按字段拼。 */
    val jdbcUrlOverride: String = "",
) : AnalyticsConnectionConfig {

    /**
     * 连接选项（契约要求）：全部由具名字段派生，保证表单草稿与落盘只有一份真相。
     * `password` 键命中 app-core 的敏感关键字，落盘自动加密。
     */
    override val options: Map<String, String>
        get() = buildMap {
            put(KEY_HOST, host)
            put(KEY_PORT, port.toString())
            put(KEY_USER, user)
            put(KEY_PASSWORD, password)
            put(KEY_DATABASE, database)
            if (jdbcUrlOverride.isNotBlank()) put(KEY_JDBC_URL, jdbcUrlOverride)
        }

    /** 完整 JDBC URL（[jdbcUrlOverride] 优先）。 */
    val jdbcUrl: String
        get() = jdbcUrlOverride.takeIf { it.isNotBlank() }
            ?: "jdbc:mysql://$host:$port/$database"

    /** 日志展示用端点（不含凭据）。 */
    val endpoint: String get() = "$host:$port"

    companion object {
        /** options 键：主机。 */
        const val KEY_HOST: String = "host"

        /** options 键：端口。 */
        const val KEY_PORT: String = "port"

        /** options 键：用户名。 */
        const val KEY_USER: String = "user"

        /** options 键：密码（敏感，落盘加密）。 */
        const val KEY_PASSWORD: String = "password"

        /** options 键：默认库。 */
        const val KEY_DATABASE: String = "database"

        /** options 键：完整 JDBC URL 覆盖。 */
        const val KEY_JDBC_URL: String = "jdbcUrl"

        /** 默认主机。 */
        const val DEFAULT_HOST: String = "localhost"

        /** StarRocks FE 的 MySQL 协议默认端口。 */
        const val DEFAULT_PORT: Int = 9030

        /** 默认用户名（StarRocks Docker 默认 root 空密码）。 */
        const val DEFAULT_USER: String = "root"

        /** 默认库（StarRocks 内置，必定存在）。 */
        const val DEFAULT_DATABASE: String = "information_schema"

        /** 从已落盘的 `ConnectionInfo.options` 还原（编辑模式 / 连接管理器用）。 */
        fun fromOptions(
            connectionId: String,
            displayName: String,
            options: Map<String, String>,
        ): StarRocksConnectionConfig = StarRocksConnectionConfig(
            connectionId = connectionId,
            displayName = displayName,
            host = options[KEY_HOST]?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST,
            port = options[KEY_PORT]?.toIntOrNull() ?: DEFAULT_PORT,
            user = options[KEY_USER] ?: DEFAULT_USER,
            password = options[KEY_PASSWORD].orEmpty(),
            database = options[KEY_DATABASE]?.takeIf { it.isNotBlank() } ?: DEFAULT_DATABASE,
            jdbcUrlOverride = options[KEY_JDBC_URL].orEmpty(),
        )
    }
}
