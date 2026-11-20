package com.mqclient.starrocks

import com.mqclient.olap.base.connection.AnalyticsConnectionConfig

/**
 * StarRocks 连接配置快照（不可变；实现 olap-base 的 [AnalyticsConnectionConfig]）。
 *
 * 字段为**具名类型字段**（host / port / user / password / database），
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

    /** 是否经 SSH 隧道连接（生产环境 FE 通常不直接暴露 9030）。 */
    val sshEnabled: Boolean = false,
    /** 堡垒机 / 跳板机地址。 */
    val sshHost: String = "",
    /** SSH 端口（默认 22）。 */
    val sshPort: Int = DEFAULT_SSH_PORT,
    /** SSH 用户名。 */
    val sshUser: String = "",
    /** SSH 密码；用私钥时它作为私钥口令（passphrase）。落盘加密。 */
    val sshPassword: String = "",
    /**
     * SSH 私钥文件路径（`~/.ssh/id_rsa` 这类）。
     *
     * 与 [sshPassword] 二选一：非空则优先用私钥认证，[sshPassword] 退化为私钥口令
     * （passphrase）。这是 OpenSSH 用户最熟悉的心智模型。
     */
    val sshKeyPath: String = "",
) : AnalyticsConnectionConfig {

    /** 是否用私钥认证（非空路径即表示用私钥）。 */
    val sshUsesKey: Boolean get() = sshKeyPath.isNotBlank()

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
            if (sshEnabled) {
                put(KEY_SSH_ENABLED, "true")
                put(KEY_SSH_HOST, sshHost)
                put(KEY_SSH_PORT, sshPort.toString())
                put(KEY_SSH_USER, sshUser)
                put(KEY_SSH_PASSWORD, sshPassword)
                if (sshKeyPath.isNotBlank()) put(KEY_SSH_KEY_PATH, sshKeyPath)
            }
        }

    /** 完整 JDBC URL（[jdbcUrlOverride] 优先，自动追加 Profile 采集会话变量）。 */
    val jdbcUrl: String
        get() = withProfileCollection(
            jdbcUrlOverride.takeIf { it.isNotBlank() }
                ?: "jdbc:mysql://$host:$port/$database",
        )

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

        /** options 键：启用 SSH 隧道。 */
        const val KEY_SSH_ENABLED: String = "sshEnabled"

        /** options 键：堡垒机地址。 */
        const val KEY_SSH_HOST: String = "sshHost"

        /** options 键：SSH 端口。 */
        const val KEY_SSH_PORT: String = "sshPort"

        /** options 键：SSH 用户名。 */
        const val KEY_SSH_USER: String = "sshUser"

        /** options 键：SSH 密码 / 私钥口令（敏感，落盘加密）。 */
        const val KEY_SSH_PASSWORD: String = "sshPassword"

        /** options 键：SSH 私钥文件路径。 */
        const val KEY_SSH_KEY_PATH: String = "sshKeyPath"

        /** SSH 默认端口。 */
        const val DEFAULT_SSH_PORT: Int = 22

        /** 默认主机。 */
        const val DEFAULT_HOST: String = "localhost"

        /** StarRocks FE 的 MySQL 协议默认端口。 */
        const val DEFAULT_PORT: Int = 9030

        /** 默认用户名（StarRocks Docker 默认 root 空密码）。 */
        const val DEFAULT_USER: String = "root"

        /** 默认库（StarRocks 内置，必定存在）。 */
        const val DEFAULT_DATABASE: String = "information_schema"

    /**
     * 从已落盘的 `ConnectionInfo.options` 还原（编辑模式 / 连接管理器用）。
     */
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
            sshEnabled = options[KEY_SSH_ENABLED] == "true",
            sshHost = options[KEY_SSH_HOST].orEmpty(),
            sshPort = options[KEY_SSH_PORT]?.toIntOrNull() ?: DEFAULT_SSH_PORT,
            sshUser = options[KEY_SSH_USER].orEmpty(),
            sshPassword = options[KEY_SSH_PASSWORD].orEmpty(),
            sshKeyPath = options[KEY_SSH_KEY_PATH].orEmpty(),
        )
    }
}

/**
 * 在 JDBC URL 上追加 Connector/J 的 `sessionVariables` 连接属性：握手后驱动自动
 * 对会话执行 `SET enable_profile = true` —— 客户端自身发起的查询因此会被
 * StarRocks 采集 Profile，「查看 Profile」闭环的采集侧兜底。
 *
 * URL 已带查询参数时用 `&` 连接；用户显式配置过 `sessionVariables` 时不覆盖
 * （尊重高级用户的自定义连接参数）。
 */
internal fun withProfileCollection(url: String): String {
    if (url.contains("sessionVariables=")) return url
    val separator = if (url.contains('?')) "&" else "?"
    return "$url${separator}sessionVariables=enable_profile=true"
}
