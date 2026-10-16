package com.mqclient.kafka

import org.apache.kafka.clients.admin.AdminClientConfig
import java.util.Properties

/**
 * Kafka 连接的特有配置：表单草稿的载体，也是 [ConnectionInfo.options] 的结构化视图
 * —— 落盘时经 [toOptions] 摊平成字符串键值（app-core 不解释内容），读回时经
 * [fromOptions] 还原。模式与 ibm-mq 的 IbmMQConfig 一致。
 *
 * 值一律存字符串（options 的契约）；类型解析在 [fromOptions] 里做，解析失败回落
 * 默认值。键名 `password` / `truststorePassword` 命中 `ConfigManager.SENSITIVE_NAME_KEYWORDS`
 * （子串匹配），落盘时由 `SensitiveOptionsSerializer` 统一加密 —— 模块内不做任何手工加密。
 */
data class KafkaConfig(
    val bootstrapServers: String = "",
    /** PLAINTEXT / SSL / SASL_PLAINTEXT / SASL_SSL（Kafka 原生协议名）。 */
    val securityProtocol: String = DEFAULT_SECURITY_PROTOCOL,
    /** securityProtocol 是 SASL_* 时生效：PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512。 */
    val saslMechanism: String = DEFAULT_SASL_MECHANISM,
    val username: String = "",
    val password: String = "",
    /** TLS 协议（SSL / SASL_SSL）可选：truststore 文件路径。空 = 用 JVM 默认信任库。 */
    val truststorePath: String = "",
    /** TLS 协议可选：truststore 密码。 */
    val truststorePassword: String = "",
    /** 连接阶段（describeCluster 验证）的超时秒数；AdminClient 的 API 超时同源。 */
    val connectionTimeout: Int = DEFAULT_CONNECTION_TIMEOUT_SECONDS,
    /** 对象慢扫间隔（分钟）：brokers / topics（分区明细）/ groups（见 KafkaScanService）。 */
    val objectScanIntervalMinutes: Int = DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES,
    /** Lag 心跳快扫间隔（秒）：consumer group 的 current/latest offset 与 lag。 */
    val lagScanIntervalSeconds: Int = DEFAULT_LAG_SCAN_INTERVAL_SECONDS,
    /**
     * Lag 告警阈值：消费组卡片右上角 Lag 超过它高亮警示（0 = 不告警）。
     * UI 侧展示语义，扫描链路不读。
     */
    val lagWarningThreshold: Long = DEFAULT_LAG_WARNING_THRESHOLD,
) {
    companion object {
        const val DEFAULT_SECURITY_PROTOCOL = "PLAINTEXT"
        const val DEFAULT_SASL_MECHANISM = "PLAIN"
        const val DEFAULT_CONNECTION_TIMEOUT_SECONDS = 30
        const val DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES = 10
        const val DEFAULT_LAG_SCAN_INTERVAL_SECONDS = 30
        const val DEFAULT_LAG_WARNING_THRESHOLD = 10_000L

        /** securityProtocol 的合法值（Kafka 原生枚举名）；fromOptions 校验用。 */
        val SECURITY_PROTOCOLS = setOf("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL")
    }
}

/** options 的键名常量：toOptions / fromOptions 两边共用，避免手写两遍字面量。 */
private object OptionKeys {
    const val BOOTSTRAP_SERVERS = "bootstrapServers"
    const val SECURITY_PROTOCOL = "securityProtocol"
    const val SASL_MECHANISM = "saslMechanism"
    const val USERNAME = "username"
    const val PASSWORD = "password"
    const val TRUSTSTORE_PATH = "truststorePath"
    const val TRUSTSTORE_PASSWORD = "truststorePassword"
    const val CONNECTION_TIMEOUT = "connectionTimeout"
    const val OBJECT_SCAN_INTERVAL = "objectScanIntervalMinutes"
    const val LAG_SCAN_INTERVAL = "lagScanIntervalSeconds"
    const val LAG_WARNING_THRESHOLD = "lagWarningThreshold"
}

/** 摊平成 [ConnectionInfo.options]（值全字符串；敏感键落盘时由序列化器加密）。 */
fun KafkaConfig.toOptions(): Map<String, String> = buildMap {
    put(OptionKeys.BOOTSTRAP_SERVERS, bootstrapServers)
    put(OptionKeys.SECURITY_PROTOCOL, securityProtocol)
    put(OptionKeys.SASL_MECHANISM, saslMechanism)
    put(OptionKeys.USERNAME, username)
    put(OptionKeys.PASSWORD, password)
    put(OptionKeys.TRUSTSTORE_PATH, truststorePath)
    put(OptionKeys.TRUSTSTORE_PASSWORD, truststorePassword)
    put(OptionKeys.CONNECTION_TIMEOUT, connectionTimeout.toString())
    put(OptionKeys.OBJECT_SCAN_INTERVAL, objectScanIntervalMinutes.toString())
    put(OptionKeys.LAG_SCAN_INTERVAL, lagScanIntervalSeconds.toString())
    put(OptionKeys.LAG_WARNING_THRESHOLD, lagWarningThreshold.toString())
}

/** 从 [ConnectionInfo.options] 还原；缺键 / 解析失败回落默认值。 */
fun kafkaConfigFromOptions(options: Map<String, String>): KafkaConfig {
    fun int(key: String, default: Int) = options[key]?.trim()?.toIntOrNull() ?: default
    fun long(key: String, default: Long) = options[key]?.trim()?.toLongOrNull() ?: default
    return KafkaConfig(
        bootstrapServers = options[OptionKeys.BOOTSTRAP_SERVERS].orEmpty(),
        securityProtocol = options[OptionKeys.SECURITY_PROTOCOL]
            ?.trim()
            ?.takeIf { it in KafkaConfig.SECURITY_PROTOCOLS }
            ?: KafkaConfig.DEFAULT_SECURITY_PROTOCOL,
        saslMechanism = options[OptionKeys.SASL_MECHANISM]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: KafkaConfig.DEFAULT_SASL_MECHANISM,
        username = options[OptionKeys.USERNAME].orEmpty(),
        password = options[OptionKeys.PASSWORD].orEmpty(),
        // 读取侧归一化（Windows 反斜杠 → 正斜杠）：旧版本表单是手写输入框，可能落进
        // `C:\Users\...` 这类裸反斜杠 —— ktoml 0.7.1 不转义 `\U` 这类 TOML 转义前缀，
        // 写出的 config.toml 自己都解析不了（读取侧矫正 + 下次落盘自愈）。
        truststorePath = options[OptionKeys.TRUSTSTORE_PATH].orEmpty().replace('\\', '/'),
        truststorePassword = options[OptionKeys.TRUSTSTORE_PASSWORD].orEmpty(),
        connectionTimeout = int(OptionKeys.CONNECTION_TIMEOUT, KafkaConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS),
        objectScanIntervalMinutes = int(
            OptionKeys.OBJECT_SCAN_INTERVAL,
            KafkaConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES,
        ),
        lagScanIntervalSeconds = int(
            OptionKeys.LAG_SCAN_INTERVAL,
            KafkaConfig.DEFAULT_LAG_SCAN_INTERVAL_SECONDS,
        ),
        lagWarningThreshold = long(
            OptionKeys.LAG_WARNING_THRESHOLD,
            KafkaConfig.DEFAULT_LAG_WARNING_THRESHOLD,
        ),
    )
}

/**
 * bootstrap / security.protocol / TLS / SASL 四块连接属性 —— AdminClient、KafkaConsumer、
 * KafkaProducer 三种客户端通用（键名同源：CommonClientConfig 的常量就是这些字符串）。
 * **刻意不带** AdminClientConfig 的 REQUEST_TIMEOUT_MS / DEFAULT_API_TIMEOUT_MS：
 * consumer/producer 里同名键的语义与量级完全不同（如 consumer 的 request.timeout.ms），
 * 复用会污染行为 —— 消息读写侧（KafkaMessageService）自己按需设。
 */
internal fun KafkaConfig.toConnectionProperties(): Properties = Properties().apply {
    put("bootstrap.servers", bootstrapServers.trim())
    put("security.protocol", securityProtocol)
    if (securityProtocol == "SSL" || securityProtocol == "SASL_SSL") {
        val truststorePath = truststorePath.trim()
        if (truststorePath.isNotEmpty()) {
            put("ssl.truststore.location", truststorePath)
            val lower = truststorePath.lowercase()
            put("ssl.truststore.type", if (lower.endsWith(".p12") || lower.endsWith(".pfx")) "PKCS12" else "JKS")
            if (truststorePassword.isNotEmpty()) {
                put("ssl.truststore.password", truststorePassword)
            }
        }
    }
    if (securityProtocol.startsWith("SASL")) {
        put("sasl.mechanism", saslMechanism)
        put("sasl.jaas.config", jaasConfig())
    }
}

/** SASL JAAS 配置串：LoginModule 类随机制走（SCRAM 与 PLAIN 各有各的实现，写死会让 SCRAM 直接报错）。 */
internal fun KafkaConfig.jaasConfig(): String {
    val loginModule = when (saslMechanism.uppercase()) {
        "SCRAM-SHA-256", "SCRAM-SHA-512" -> "org.apache.kafka.common.security.scram.ScramLoginModule"
        else -> "org.apache.kafka.common.security.plain.PlainLoginModule"
    }
    return buildString {
        append(loginModule).append(" required ")
        append("username=\"").append(username.escapeJaas()).append("\" ")
        append("password=\"").append(password.escapeJaas()).append("\";")
    }
}

/**
 * 组装 [AdminClient] 的连接属性：连接四块见 [toConnectionProperties]，另加 AdminClient
 * 专属的两个超时（与 [KafkaConfig.connectionTimeout] 同源）。
 */
fun KafkaConfig.toAdminProperties(): Properties = Properties().apply {
    putAll(toConnectionProperties())
    put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, connectionTimeout * 1_000)
    put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, connectionTimeout * 1_000)
}

/** JAAS 配置串的最小转义：反斜杠与双引号。 */
private fun String.escapeJaas(): String = replace("\\", "\\\\").replace("\"", "\\\"")
