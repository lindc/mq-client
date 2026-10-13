package com.mqclient.kafka

import org.apache.kafka.clients.admin.AdminClientConfig
import java.util.Properties

/**
 * Kafka 连接的特有配置：表单草稿的载体，也是 [ConnectionInfo.options] 的结构化视图
 * —— 落盘时经 [toOptions] 摊平成字符串键值（app-core 不解释内容），读回时经
 * [fromOptions] 还原。模式与 ibm-mq 的 IbmMQConfig 一致。
 *
 * 值一律存字符串（options 的契约）；类型解析在 [fromOptions] 里做，解析失败回落
 * 默认值。键名 `password` 命中 `ConfigManager.SENSITIVE_NAME_KEYWORDS`，落盘时由
 * `SensitiveOptionsSerializer` 统一加密 —— 模块内不做任何手工加密。
 *
 * 阶段一先只放「连得上」所需的字段；SSL 证书、扫描间隔等留待对应阶段再补。
 */
data class KafkaConfig(
    val bootstrapServers: String = "",
    /** PLAINTEXT / SSL / SASL_PLAINTEXT / SASL_SSL（Kafka 原生协议名）。 */
    val securityProtocol: String = DEFAULT_SECURITY_PROTOCOL,
    /** securityProtocol 是 SASL_* 时生效：PLAIN / SCRAM-SHA-256 / SCRAM-SHA-512。 */
    val saslMechanism: String = DEFAULT_SASL_MECHANISM,
    val username: String = "",
    val password: String = "",
    /** 连接阶段（describeCluster 验证）的超时秒数；AdminClient 的 API 超时同源。 */
    val connectionTimeout: Int = DEFAULT_CONNECTION_TIMEOUT_SECONDS,
) {
    companion object {
        const val DEFAULT_SECURITY_PROTOCOL = "PLAINTEXT"
        const val DEFAULT_SASL_MECHANISM = "PLAIN"
        const val DEFAULT_CONNECTION_TIMEOUT_SECONDS = 30

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
    const val CONNECTION_TIMEOUT = "connectionTimeout"
}

/** 摊平成 [ConnectionInfo.options]（值全字符串；敏感键落盘时由序列化器加密）。 */
fun KafkaConfig.toOptions(): Map<String, String> = buildMap {
    put(OptionKeys.BOOTSTRAP_SERVERS, bootstrapServers)
    put(OptionKeys.SECURITY_PROTOCOL, securityProtocol)
    put(OptionKeys.SASL_MECHANISM, saslMechanism)
    put(OptionKeys.USERNAME, username)
    put(OptionKeys.PASSWORD, password)
    put(OptionKeys.CONNECTION_TIMEOUT, connectionTimeout.toString())
}

/** 从 [ConnectionInfo.options] 还原；缺键 / 解析失败回落默认值。 */
fun kafkaConfigFromOptions(options: Map<String, String>): KafkaConfig {
    fun int(key: String, default: Int) = options[key]?.trim()?.toIntOrNull() ?: default
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
        connectionTimeout = int(OptionKeys.CONNECTION_TIMEOUT, KafkaConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS),
    )
}

/**
 * 组装 [AdminClient] 的连接属性。只有 SASL_* 协议才注入 JAAS 配置 —— PLAINTEXT/SSL
 * 下给了用户名也没意义。JAAS 字符串里对 username/password 做最小转义（反斜杠、双引
 * 号），避免特殊字符把配置炸出解析错误。
 */
fun KafkaConfig.toAdminProperties(): Properties = Properties().apply {
    put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers.trim())
    put(AdminClientConfig.SECURITY_PROTOCOL_CONFIG, securityProtocol)
    put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, connectionTimeout * 1_000)
    put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, connectionTimeout * 1_000)
    if (securityProtocol.startsWith("SASL")) {
        put("sasl.mechanism", saslMechanism)
        // LoginModule 类随机制走：SCRAM 与 PLAIN 各有各的实现，写死会让 SCRAM 连接直接报错。
        val loginModule = when (saslMechanism.uppercase()) {
            "SCRAM-SHA-256", "SCRAM-SHA-512" -> "org.apache.kafka.common.security.scram.ScramLoginModule"
            else -> "org.apache.kafka.common.security.plain.PlainLoginModule"
        }
        put(
            "sasl.jaas.config",
            buildString {
                append(loginModule).append(" required ")
                append("username=\"").append(username.escapeJaas()).append("\" ")
                append("password=\"").append(password.escapeJaas()).append("\";")
            },
        )
    }
}

/** JAAS 配置串的最小转义：反斜杠与双引号。 */
private fun String.escapeJaas(): String = replace("\\", "\\\\").replace("\"", "\\\"")
