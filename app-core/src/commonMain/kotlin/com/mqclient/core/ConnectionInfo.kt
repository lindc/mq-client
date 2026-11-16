package com.mqclient.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 一条连接的配置快照。原在 jvmMain 的 ConfigModel.kt，随 [ConnectionRegistry]
 * 迁入 commonMain —— HostFacade 搬家后 `ServiceContext.connectionRegistry` 是
 * commonMain 接口，成员签名必须引用本类型。
 *
 * The concrete credential fields a middleware needs are decided by its own
 * service module (per the single-file spec, connections live in config.toml);
 * the [options] map values whose key matches the sensitive keywords
 * (password, apiKey, …) are encrypted by [SensitiveOptionsSerializer].
 */
@Serializable
data class ConnectionInfo(
    val id: String,
    /** User-facing name, e.g. "生产环境". */
    val name: String,
    val serviceId: String,
    /** Name of the MQ endpoint itself, e.g. "rabbit@mq-01". */
    val mqName: String,
    val host: String,
    val port: Int,
    /**
     * 该中间件自己的扩展字段（vhost / channel / queue manager / SASL 机制 …），键值都是字符串。
     *
     * 这是对话框按 [serviceId] 渲染字段的挂靠点：各 service 模块声明自己要哪些字段，
     * 值原样存进这里 —— app-core 不解释内容，也不需要为每个中间件改一次模型。
     * 键名命中敏感关键字的项（password / token / secret …）由 [SensitiveOptionsSerializer]
     * 落盘加密，和普通字段同一套规则。
     */
    @Serializable(with = SensitiveOptionsSerializer::class)
    val options: Map<String, String> = emptyMap(),
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
) {
    val endpoint: String get() = "$host:$port"
}

/** Live state of a connection, as shown by the sidebar's status dot and label.
 *
 * 展示文案走 i18n 键（`err_status_*`，ui-common 公共表）—— 核心层不产出面向
 * 用户的文案，侧栏在渲染时点经 stringResource 翻译。
 */
@Serializable
enum class ConnectionStatus {
    Connected,
    Connecting,
    Failed,
    Disconnected,
}

/**
 * [ConnectionInfo.options] 的序列化器：键名命中 [SENSITIVE_NAME_KEYWORDS]
 * 的值在编码时加密；解码时凡带 [ENCRYPTED_VALUE_PREFIX] 的值统一解密
 * （明文原样放行，解密失败保留密文，见 [decryptStoredValue]）。
 *
 * 键本身是用户/服务自定义的，只能按名字识别敏感性 —— 这是树遍历之外唯一
 * 需要名字匹配的地方。
 */
object SensitiveOptionsSerializer : KSerializer<Map<String, String>> {
    private val delegate = MapSerializer(String.serializer(), String.serializer())
    override val descriptor = SerialDescriptor("mqclient.SensitiveOptions", delegate.descriptor)

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        delegate.serialize(
            encoder,
            value.mapValues { (key, v) ->
                val lower = key.lowercase()
                if (SENSITIVE_NAME_KEYWORDS.any { lower.contains(it) }) {
                    encryptStoredValue(v)
                } else {
                    v
                }
            },
        )
    }

    override fun deserialize(decoder: Decoder): Map<String, String> =
        delegate.deserialize(decoder).mapValues { (_, v) -> decryptStoredValue(v) }
}
