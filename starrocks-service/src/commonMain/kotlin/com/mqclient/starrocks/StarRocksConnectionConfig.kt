package com.mqclient.starrocks

import com.mqclient.olap.base.connection.AnalyticsConnectionConfig

/**
 * StarRocks 连接配置快照（不可变；对齐 olap-base 的 `AnalyticsConnectionConfig`）。
 *
 * 与仓库 MQ 系同口径：协议特有配置放 [options]，`host` / `port` / `user` /
 * `password` 由 `ConnectionFormUi` 写入，敏感项在落盘层由 app-core 的
 * `SensitiveOptionsSerializer` 加密，本模块不解释内容。
 *
 * [jdbcUrl] 允许直接给完整 URL（便于真机验证时带参数）；缺省按
 * `jdbc:mysql://<host>:<port>/` 拼（StarRocks FE 的 MySQL 协议端口默认 9030）。
 *
 * S1 阶段只用到连接与探针，不做 catalog / query 配置。
 */
data class StarRocksConnectionConfig(
    override val connectionId: String,
    override val displayName: String,
    override val options: Map<String, String> = emptyMap(),
) : AnalyticsConnectionConfig {

    /** 完整 JDBC URL（options 里显式给出时优先）。 */
    val jdbcUrl: String
        get() = options[KEY_JDBC_URL]?.takeIf { it.isNotBlank() }
            ?: "jdbc:mysql://${options[KEY_HOST] ?: "localhost"}:${options[KEY_PORT] ?: DEFAULT_PORT}/"

    /** 用户名。 */
    val user: String get() = options[KEY_USER].orEmpty()

    /** 密码（明文只在进程内；落盘由 app-core 加密）。 */
    val password: String get() = options[KEY_PASSWORD].orEmpty()

    /** 日志里展示的端点（不打印密码）。 */
    val endpoint: String get() = jdbcUrl.substringAfter("jdbc:mysql://").substringBefore("/")

    companion object {
        /** options 键：完整 JDBC URL（优先级最高）。 */
        const val KEY_JDBC_URL: String = "jdbcUrl"

        /** options 键：主机名。 */
        const val KEY_HOST: String = "host"

        /** options 键：端口（StarRocks FE query port，默认 9030）。 */
        const val KEY_PORT: String = "port"

        /** options 键：用户名。 */
        const val KEY_USER: String = "user"

        /** options 键：密码。 */
        const val KEY_PASSWORD: String = "password"

        /** StarRocks FE 的 MySQL 协议默认端口。 */
        const val DEFAULT_PORT: String = "9030"
    }
}
