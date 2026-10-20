package com.mqclient.rocketmq

/**
 * RocketMQ 连接的特有配置：表单草稿的载体，也是 [ConnectionInfo.options] 的结构化
 * 视图 —— 落盘时经 [toOptions] 摊平成字符串键值（app-core 不解释内容），读回时经
 * [rocketMqConfigFromOptions] 还原。模式与 KafkaConfig / RabbitMqConfig 一致。
 *
 * 值一律存字符串（options 的契约）；类型解析在读回侧做，解析失败回落默认值。
 * 键名 `secretKey` 命中 `ConfigManager.SENSITIVE_NAME_KEYWORDS`（子串匹配），
 * 落盘时由 `SensitiveOptionsSerializer` 统一加密（AES-256-GCM，密钥
 * `~/.mqclient/secret.key`）—— 模块内不做任何手工加密。
 *
 * RocketMQ 的连接入口是 **NameServer**（Remoting 协议，默认 9876）：多个地址用
 * 分号分隔，客户端自己做 failover。阶段 1 不涉及 TLS（Remoting TLS 是集群侧
 * 配置项，常规部署未开启）。
 */
data class RocketMqConfig(
    /** NameServer 地址，多个用分号分隔（如 `10.0.0.1:9876;10.0.0.2:9876`）。 */
    val namesrvAddr: String = "",
    /** ACL AccessKey（集群未开 ACL 留空）。 */
    val accessKey: String = "",
    /** ACL SecretKey（敏感，落盘自动加密）。 */
    val secretKey: String = "",
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
    const val CONNECTION_TIMEOUT = "connectionTimeout"
    const val OBJECT_SCAN_INTERVAL = "objectScanIntervalMinutes"
    const val STATUS_SCAN_INTERVAL = "statusScanIntervalSeconds"
}

/** 摊平成 [ConnectionInfo.options]（值全字符串；敏感键落盘时由序列化器加密）。 */
fun RocketMqConfig.toOptions(): Map<String, String> = buildMap {
    put(OptionKeys.NAMESRV_ADDR, namesrvAddr)
    put(OptionKeys.ACCESS_KEY, accessKey)
    put(OptionKeys.SECRET_KEY, secretKey)
    put(OptionKeys.CONNECTION_TIMEOUT, connectionTimeout.toString())
    put(OptionKeys.OBJECT_SCAN_INTERVAL, objectScanIntervalMinutes.toString())
    put(OptionKeys.STATUS_SCAN_INTERVAL, statusScanIntervalSeconds.toString())
}

/** 从 [ConnectionInfo.options] 还原；缺键 / 解析失败回落默认值。 */
fun rocketMqConfigFromOptions(options: Map<String, String>): RocketMqConfig {
    fun int(key: String, default: Int) = options[key]?.trim()?.toIntOrNull() ?: default
    return RocketMqConfig(
        namesrvAddr = options[OptionKeys.NAMESRV_ADDR].orEmpty(),
        accessKey = options[OptionKeys.ACCESS_KEY].orEmpty(),
        secretKey = options[OptionKeys.SECRET_KEY].orEmpty(),
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
