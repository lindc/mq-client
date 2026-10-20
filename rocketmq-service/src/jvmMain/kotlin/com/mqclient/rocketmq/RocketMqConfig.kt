package com.mqclient.rocketmq

/**
 * RocketMQ 连接的特有配置：表单草稿的载体，也是 [ConnectionInfo.options] 的结构化
 * 视图 —— 落盘时经 [toOptions] 摊平成字符串键值（app-core 不解释内容），读回时经
 * [rocketMqConfigFromOptions] 还原。模式与 KafkaConfig / RabbitMqConfig 一致。
 *
 * 值一律存字符串（options 的契约）；类型解析在读回侧做，解析失败回落默认值。
 * 键名 `secretKey` / `*Password` 命中 `ConfigManager.SENSITIVE_NAME_KEYWORDS`
 * （子串匹配），落盘时由 `SensitiveOptionsSerializer` 统一加密（AES-256-GCM，密钥
 * `~/.mqclient/secret.key`）—— 模块内不做任何手工加密。
 *
 * RocketMQ 的连接入口是 **NameServer**（Remoting 协议，默认 9876）：多个地址用
 * 分号分隔，客户端自己做 failover。
 *
 * TLS：客户端侧走 Remoting 的 SSL（开关 `tls.enable` + `TlsSystemConfig` 证书
 * 字段，见 RocketMqAdminClient 的注入逻辑）。证书文件是 **JKS/P12**（与 Kafka
 * 表单同一套），由 [TlsPemExport] 转成客户端要的 PEM 再喂给 Netty。
 */
data class RocketMqConfig(
    /** NameServer 地址，多个用分号分隔（如 `10.0.0.1:9876;10.0.0.2:9876`）。 */
    val namesrvAddr: String = "",
    /** ACL AccessKey（集群未开 ACL 留空）。 */
    val accessKey: String = "",
    /** ACL SecretKey（敏感，落盘自动加密）。 */
    val secretKey: String = "",
    /** 是否启用 TLS：Remoting 客户端以 SSL 连接 NameServer / Broker。 */
    val tlsEnabled: Boolean = false,
    /** TLS 开启时是否校验服务器证书；关 = 信任所有证书（仅建议联调环境）。 */
    val tlsVerifyServer: Boolean = true,
    /** TLS + 校验服务器时必填：Truststore 文件（.p12/.pfx → PKCS12，其余 → JKS）。 */
    val trustStorePath: String = "",
    /** Truststore 密码（敏感，落盘自动加密）。 */
    val trustStorePassword: String = "",
    /** 双向认证可选：客户端 Keystore 文件（含私钥 + 证书链）。 */
    val keyStorePath: String = "",
    /** Keystore 密码（敏感，落盘自动加密）。 */
    val keyStorePassword: String = "",
    /** 连接阶段（cluster info 验证）与管理 API 调用的超时秒数。 */
    val connectionTimeout: Int = DEFAULT_CONNECTION_TIMEOUT_SECONDS,
    /** 对象慢扫间隔（分钟）：clusterInfo / topics / groups。 */
    val objectScanIntervalMinutes: Int = DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES,
    /** 状态快扫间隔（秒）：消费组位点 / Lag。 */
    val statusScanIntervalSeconds: Int = DEFAULT_STATUS_SCAN_INTERVAL_SECONDS,
) {
    companion object {
        const val DEFAULT_CONNECTION_TIMEOUT_SECONDS = 30
        const val DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES = 5
        const val DEFAULT_STATUS_SCAN_INTERVAL_SECONDS = 15
    }
}

/** options 的键名常量：toOptions / fromOptions 两边共用，避免手写两遍字面量。 */
private object OptionKeys {
    const val NAMESRV_ADDR = "namesrvAddr"
    const val ACCESS_KEY = "accessKey"
    const val SECRET_KEY = "secretKey"
    const val TLS_ENABLED = "tlsEnabled"
    const val TLS_VERIFY_SERVER = "tlsVerifyServer"
    const val TRUST_STORE_PATH = "trustStorePath"
    const val TRUST_STORE_PASSWORD = "trustStorePassword"
    const val KEY_STORE_PATH = "keyStorePath"
    const val KEY_STORE_PASSWORD = "keyStorePassword"
    const val CONNECTION_TIMEOUT = "connectionTimeout"
    const val OBJECT_SCAN_INTERVAL = "objectScanIntervalMinutes"
    const val STATUS_SCAN_INTERVAL = "statusScanIntervalSeconds"
}

/** 摊平成 [ConnectionInfo.options]（值全字符串；敏感键落盘时由序列化器加密）。 */
fun RocketMqConfig.toOptions(): Map<String, String> = buildMap {
    put(OptionKeys.NAMESRV_ADDR, namesrvAddr)
    put(OptionKeys.ACCESS_KEY, accessKey)
    put(OptionKeys.SECRET_KEY, secretKey)
    put(OptionKeys.TLS_ENABLED, tlsEnabled.toString())
    put(OptionKeys.TLS_VERIFY_SERVER, tlsVerifyServer.toString())
    put(OptionKeys.TRUST_STORE_PATH, trustStorePath)
    put(OptionKeys.TRUST_STORE_PASSWORD, trustStorePassword)
    put(OptionKeys.KEY_STORE_PATH, keyStorePath)
    put(OptionKeys.KEY_STORE_PASSWORD, keyStorePassword)
    put(OptionKeys.CONNECTION_TIMEOUT, connectionTimeout.toString())
    put(OptionKeys.OBJECT_SCAN_INTERVAL, objectScanIntervalMinutes.toString())
    put(OptionKeys.STATUS_SCAN_INTERVAL, statusScanIntervalSeconds.toString())
}

/** 从 [ConnectionInfo.options] 还原；缺键 / 解析失败回落默认值。 */
fun rocketMqConfigFromOptions(options: Map<String, String>): RocketMqConfig {
    fun int(key: String, default: Int) = options[key]?.trim()?.toIntOrNull() ?: default
    fun bool(key: String, default: Boolean) = when (options[key]?.trim()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> default
    }
    return RocketMqConfig(
        namesrvAddr = options[OptionKeys.NAMESRV_ADDR].orEmpty(),
        accessKey = options[OptionKeys.ACCESS_KEY].orEmpty(),
        secretKey = options[OptionKeys.SECRET_KEY].orEmpty(),
        tlsEnabled = bool(OptionKeys.TLS_ENABLED, false),
        tlsVerifyServer = bool(OptionKeys.TLS_VERIFY_SERVER, true),
        trustStorePath = options[OptionKeys.TRUST_STORE_PATH].orEmpty().replace('\\', '/'),
        trustStorePassword = options[OptionKeys.TRUST_STORE_PASSWORD].orEmpty(),
        keyStorePath = options[OptionKeys.KEY_STORE_PATH].orEmpty().replace('\\', '/'),
        keyStorePassword = options[OptionKeys.KEY_STORE_PASSWORD].orEmpty(),
        connectionTimeout = int(OptionKeys.CONNECTION_TIMEOUT, RocketMqConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS),
        objectScanIntervalMinutes = int(
            OptionKeys.OBJECT_SCAN_INTERVAL,
            RocketMqConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES,
        ),
        statusScanIntervalSeconds = int(
            OptionKeys.STATUS_SCAN_INTERVAL,
            RocketMqConfig.DEFAULT_STATUS_SCAN_INTERVAL_SECONDS,
        ),
    )
}
