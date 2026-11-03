package com.mqclient.ibmmq

/**
 * IBM MQ 连接的特有配置：表单草稿的载体，也是 [ConnectionInfo.options] 的
 * 结构化视图 —— 落盘时经 [toOptions] 摊平成字符串键值（app-core 不解释内容），
 * 读回时经 [fromOptions] 还原。
 *
 * 值一律存字符串（options 的契约）；类型解析在 [fromOptions] 里做，解析失败
 * 回落到默认值 —— 手改 config.toml 填错数字不至于让连接读不出来。
 *
 * 敏感字段（password / trustStorePassword / keyStorePassword）在这里都是明文，
 * 键名命中 `ConfigManager.SENSITIVE_NAME_KEYWORDS`（含 "password"），落盘时由
 * `SensitiveOptionsSerializer` 统一加密 —— 模块内不做任何手工加密。
 */
data class IbmMQConfig(
    val mode: ConnectionMode = ConnectionMode.CLIENT,
    val queueManager: String = "",
    val host: String = "",
    val port: Int = DEFAULT_PORT,
    val channel: String = DEFAULT_CHANNEL,
    val ccsid: Int = DEFAULT_CCSID,
    val username: String = "",
    val password: String = "",
    val sslEnabled: Boolean = false,
    val trustStorePath: String = "",
    val trustStorePassword: String = "",
    val keyStorePath: String = "",
    val keyStorePassword: String = "",
    val cipherSuite: String = "",
    val peerName: String = "",
    val connectionTimeout: Int = DEFAULT_CONNECTION_TIMEOUT_SECONDS,
    /** 消息数量扫描间隔（秒），对应 5 秒心跳任务的节奏微调。 */
    val messageScanIntervalSeconds: Int = DEFAULT_MESSAGE_SCAN_INTERVAL_SECONDS,
    /** 对象列表（队列/主题/监听器）扫描间隔（分钟）。 */
    val objectScanIntervalMinutes: Int = DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES,
    /** 队列深度告警阈值（百分比，50-100）：队列使用率超过它时界面显示告警。 */
    val queueDepthWarningThreshold: Int = 85,
) {
    companion object {
        const val DEFAULT_PORT = 1414
        const val DEFAULT_CHANNEL = "SYSTEM.DEF.SVRCONN"
        const val DEFAULT_CCSID = 1208
        const val DEFAULT_CONNECTION_TIMEOUT_SECONDS = 30
        const val DEFAULT_MESSAGE_SCAN_INTERVAL_SECONDS = 10
        const val DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES = 60
        const val DEFAULT_QUEUE_DEPTH_WARNING_THRESHOLD = 85
    }
}

/** IBM MQ 的两种连接方式：网络（TCP 到 host:port）或本地绑定（同机 JNI）。 */
enum class ConnectionMode { CLIENT, BINDING }

/** options 的键名常量：toOptions / fromOptions 两边共用，避免手写两遍字面量。 */
private object OptionKeys {
    const val MODE = "mode"
    const val QUEUE_MANAGER = "queueManager"
    const val HOST = "host"
    const val PORT = "port"
    const val CHANNEL = "channel"
    const val CCSID = "ccsid"
    const val USERNAME = "username"
    const val PASSWORD = "password"
    const val SSL_ENABLED = "sslEnabled"
    const val TRUST_STORE_PATH = "trustStorePath"
    const val TRUST_STORE_PASSWORD = "trustStorePassword"
    const val KEY_STORE_PATH = "keyStorePath"
    const val KEY_STORE_PASSWORD = "keyStorePassword"
    const val CIPHER_SUITE = "cipherSuite"
    const val PEER_NAME = "peerName"
    const val CONNECTION_TIMEOUT = "connectionTimeout"
    const val MESSAGE_SCAN_INTERVAL = "messageScanIntervalSeconds"
    const val OBJECT_SCAN_INTERVAL = "objectScanIntervalMinutes"
    const val QUEUE_DEPTH_WARNING_THRESHOLD = "queueDepthWarningThreshold"
}

/** 摊平成 [ConnectionInfo.options]（值全字符串；敏感键落盘时由序列化器加密）。 */
fun IbmMQConfig.toOptions(): Map<String, String> = buildMap {
    put(OptionKeys.MODE, mode.name)
    put(OptionKeys.QUEUE_MANAGER, queueManager)
    put(OptionKeys.HOST, host)
    put(OptionKeys.PORT, port.toString())
    put(OptionKeys.CHANNEL, channel)
    put(OptionKeys.CCSID, ccsid.toString())
    put(OptionKeys.USERNAME, username)
    put(OptionKeys.PASSWORD, password)
    put(OptionKeys.SSL_ENABLED, sslEnabled.toString())
    put(OptionKeys.TRUST_STORE_PATH, trustStorePath)
    put(OptionKeys.TRUST_STORE_PASSWORD, trustStorePassword)
    put(OptionKeys.KEY_STORE_PATH, keyStorePath)
    put(OptionKeys.KEY_STORE_PASSWORD, keyStorePassword)
    put(OptionKeys.CIPHER_SUITE, cipherSuite)
    put(OptionKeys.PEER_NAME, peerName)
    put(OptionKeys.CONNECTION_TIMEOUT, connectionTimeout.toString())
    put(OptionKeys.MESSAGE_SCAN_INTERVAL, messageScanIntervalSeconds.toString())
    put(OptionKeys.OBJECT_SCAN_INTERVAL, objectScanIntervalMinutes.toString())
    put(OptionKeys.QUEUE_DEPTH_WARNING_THRESHOLD, queueDepthWarningThreshold.toString())
}

/** 从 [ConnectionInfo.options] 还原；缺键 / 解析失败回落默认值。 */
fun ibmMQConfigFromOptions(options: Map<String, String>): IbmMQConfig {
    fun int(key: String, default: Int) = options[key]?.trim()?.toIntOrNull() ?: default
    return IbmMQConfig(
        mode = options[OptionKeys.MODE]
            ?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() }
            ?: ConnectionMode.CLIENT,
        queueManager = options[OptionKeys.QUEUE_MANAGER].orEmpty(),
        host = options[OptionKeys.HOST].orEmpty(),
        port = int(OptionKeys.PORT, IbmMQConfig.DEFAULT_PORT),
        channel = options[OptionKeys.CHANNEL] ?: IbmMQConfig.DEFAULT_CHANNEL,
        ccsid = int(OptionKeys.CCSID, IbmMQConfig.DEFAULT_CCSID),
        username = options[OptionKeys.USERNAME].orEmpty(),
        password = options[OptionKeys.PASSWORD].orEmpty(),
        sslEnabled = options[OptionKeys.SSL_ENABLED]?.toBooleanStrictOrNull() ?: false,
        trustStorePath = options[OptionKeys.TRUST_STORE_PATH].orEmpty(),
        trustStorePassword = options[OptionKeys.TRUST_STORE_PASSWORD].orEmpty(),
        keyStorePath = options[OptionKeys.KEY_STORE_PATH].orEmpty(),
        keyStorePassword = options[OptionKeys.KEY_STORE_PASSWORD].orEmpty(),
        cipherSuite = options[OptionKeys.CIPHER_SUITE].orEmpty(),
        peerName = options[OptionKeys.PEER_NAME].orEmpty(),
        connectionTimeout = int(OptionKeys.CONNECTION_TIMEOUT, IbmMQConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS),
        messageScanIntervalSeconds = int(OptionKeys.MESSAGE_SCAN_INTERVAL, IbmMQConfig.DEFAULT_MESSAGE_SCAN_INTERVAL_SECONDS),
        objectScanIntervalMinutes = int(OptionKeys.OBJECT_SCAN_INTERVAL, IbmMQConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES),
        queueDepthWarningThreshold =
            int(OptionKeys.QUEUE_DEPTH_WARNING_THRESHOLD, IbmMQConfig.DEFAULT_QUEUE_DEPTH_WARNING_THRESHOLD),
    )
}
