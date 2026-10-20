package com.mqclient.rabbitmq

/**
 * RabbitMQ 连接的特有配置：表单草稿的载体，也是 [ConnectionInfo.options] 的结构化视图
 * —— 落盘时经 [toOptions] 摊平成字符串键值（app-core 不解释内容），读回时经
 * [fromOptions] 还原。模式与 KafkaConfig / IbmMQConfig 一致。
 *
 * 值一律存字符串（options 的契约）；类型解析在 [fromOptions] 里做，解析失败回落
 * 默认值。键名 `password` 命中 `ConfigManager.SENSITIVE_NAME_KEYWORDS`（子串匹配），
 * 落盘时由 `SensitiveOptionsSerializer` 统一加密（AES-256-GCM，密钥
 * `~/.mqclient/secret.key`）—— 模块内不做任何手工加密。
 *
 * RabbitMQ 的特殊性：**两套端口** —— AMQP 0.9.1（消息收发，5672）与管理 API
 * （HTTP，15672）。第一阶段的连接验证 / 扫描全部走管理 API；amqp-client 引入但暂不使用。
 */
data class RabbitMqConfig(
    val host: String = "",
    /** AMQP 0.9.1 消息端口（消息收发阶段用；第一阶段只落盘展示）。 */
    val amqpPort: Int = DEFAULT_AMQP_PORT,
    /** 管理 API 端口（HTTP，rabbitmq-management 插件）。 */
    val managementPort: Int = DEFAULT_MANAGEMENT_PORT,
    /** 虚拟主机；默认根 vhost。管理 API URL 里按整段编码（"/" → %2F）。 */
    val vhost: String = DEFAULT_VHOST,
    val username: String = "",
    val password: String = "",
    /** true = AMQP 走 5671 TLS、管理 API 走 https。 */
    val useTls: Boolean = false,
    /** TLS 信任库路径（PKCS12，自签 CA 场景）；空 = JVM 默认信任链。 */
    val tlsTruststorePath: String = "",
    /** TLS 信任库密码。 */
    val tlsTruststorePassword: String = "",
    /** 连接阶段（overview 验证）与管理 API 请求的超时秒数。 */
    val connectionTimeout: Int = DEFAULT_CONNECTION_TIMEOUT_SECONDS,
    /** 对象慢扫间隔（分钟）：exchanges / queues / bindings（见 RabbitMqScanService）。 */
    val objectScanIntervalMinutes: Int = DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES,
    /** 状态快扫间隔（秒）：队列的消息数 / 消费者数。 */
    val statusScanIntervalSeconds: Int = DEFAULT_STATUS_SCAN_INTERVAL_SECONDS,
) {
    companion object {
        const val DEFAULT_AMQP_PORT = 5672
        const val DEFAULT_MANAGEMENT_PORT = 15672
        const val DEFAULT_VHOST = "/"
        const val DEFAULT_CONNECTION_TIMEOUT_SECONDS = 30
        const val DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES = 5
        const val DEFAULT_STATUS_SCAN_INTERVAL_SECONDS = 10
    }
}

/** options 的键名常量：toOptions / fromOptions 两边共用，避免手写两遍字面量。 */
private object OptionKeys {
    const val HOST = "host"
    const val AMQP_PORT = "amqpPort"
    const val MANAGEMENT_PORT = "managementPort"
    const val VHOST = "vhost"
    const val USERNAME = "username"
    const val PASSWORD = "password"
    const val USE_TLS = "useTls"
    const val TLS_TRUSTSTORE_PATH = "tlsTruststorePath"
    const val TLS_TRUSTSTORE_PASSWORD = "tlsTruststorePassword"
    const val CONNECTION_TIMEOUT = "connectionTimeout"
    const val OBJECT_SCAN_INTERVAL = "objectScanIntervalMinutes"
    const val STATUS_SCAN_INTERVAL = "statusScanIntervalSeconds"
}

/** 摊平成 [ConnectionInfo.options]（值全字符串；敏感键落盘时由序列化器加密）。 */
fun RabbitMqConfig.toOptions(): Map<String, String> = buildMap {
    put(OptionKeys.HOST, host)
    put(OptionKeys.AMQP_PORT, amqpPort.toString())
    put(OptionKeys.MANAGEMENT_PORT, managementPort.toString())
    put(OptionKeys.VHOST, vhost)
    put(OptionKeys.USERNAME, username)
    put(OptionKeys.PASSWORD, password)
    put(OptionKeys.USE_TLS, useTls.toString())
    put(OptionKeys.TLS_TRUSTSTORE_PATH, tlsTruststorePath)
    put(OptionKeys.TLS_TRUSTSTORE_PASSWORD, tlsTruststorePassword)
    put(OptionKeys.CONNECTION_TIMEOUT, connectionTimeout.toString())
    put(OptionKeys.OBJECT_SCAN_INTERVAL, objectScanIntervalMinutes.toString())
    put(OptionKeys.STATUS_SCAN_INTERVAL, statusScanIntervalSeconds.toString())
}

/** 从 [ConnectionInfo.options] 还原；缺键 / 解析失败回落默认值。 */
fun rabbitMqConfigFromOptions(options: Map<String, String>): RabbitMqConfig {
    fun int(key: String, default: Int) = options[key]?.trim()?.toIntOrNull() ?: default
    fun boolean(key: String, default: Boolean) =
        options[key]?.trim()?.lowercase()?.let { it == "true" || it == "1" } ?: default
    return RabbitMqConfig(
        host = options[OptionKeys.HOST].orEmpty(),
        amqpPort = int(OptionKeys.AMQP_PORT, RabbitMqConfig.DEFAULT_AMQP_PORT),
        managementPort = int(OptionKeys.MANAGEMENT_PORT, RabbitMqConfig.DEFAULT_MANAGEMENT_PORT),
        vhost = options[OptionKeys.VHOST]?.takeIf { it.isNotEmpty() } ?: RabbitMqConfig.DEFAULT_VHOST,
        username = options[OptionKeys.USERNAME].orEmpty(),
        password = options[OptionKeys.PASSWORD].orEmpty(),
        useTls = boolean(OptionKeys.USE_TLS, false),
        tlsTruststorePath = options[OptionKeys.TLS_TRUSTSTORE_PATH].orEmpty(),
        tlsTruststorePassword = options[OptionKeys.TLS_TRUSTSTORE_PASSWORD].orEmpty(),
        connectionTimeout = int(OptionKeys.CONNECTION_TIMEOUT, RabbitMqConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS),
        objectScanIntervalMinutes = int(
            OptionKeys.OBJECT_SCAN_INTERVAL,
            RabbitMqConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES,
        ),
        statusScanIntervalSeconds = int(
            OptionKeys.STATUS_SCAN_INTERVAL,
            RabbitMqConfig.DEFAULT_STATUS_SCAN_INTERVAL_SECONDS,
        ),
    )
}
