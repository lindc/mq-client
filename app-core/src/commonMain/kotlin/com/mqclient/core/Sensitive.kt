package com.mqclient.core

import com.mqclient.core.log.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Marks a config-model property whose value must be encrypted at rest.
 *
 * The encryption itself is carried by the serializer attached to the same
 * property (`@Serializable(with = EncryptedStringSerializer::class)`):
 * encoding encrypts, decoding decrypts — ConfigManager no longer walks the
 * serialized tree. The annotation stays as the human-readable marker of the
 * intent (and pairs with the field-level keyword rules inside
 * `ConnectionInfo.options`, where keys are user/service-defined and can only
 * be matched by name).
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Sensitive

/** Prefix distinguishing encrypted values inside the config file. */
const val ENCRYPTED_VALUE_PREFIX = "enc:v1:"

/**
 * Provides the AES key bytes for the sensitive-field serializers. The key
 * lives in `secret.key`, owned by [ConfigManager] (platform side) — hence the
 * expect/actual indirection: the serializers must live in commonMain next to
 * the models, while key material is managed by the jvm ConfigManager.
 */
expect fun sensitiveEncryptionKey(): ByteArray

/**
 * Encrypts [plaintext] into a `enc:v1:…` stored value — **幂等**：值已经是密文时原样返回。
 *
 * 为什么必须幂等：解密失败时 [decryptStoredValue] 会**保留密文**（宁可留原文也不
 * 破坏字段），于是内存里可能拿着一个 `enc:v1:…` 字符串。下一次保存若再套一层，
 * 磁盘上就变成「密文的密文」—— 用户之后即使把正确的 `secret.key` 放回去也永远解
 * 不开（解出来仍是 `enc:v1:…` 字面量），每保存一次多嵌一层。
 *
 * 触发场景都很日常：换了机器没带 `secret.key`、配置从别处拷贝、密钥文件被误删后
 * 重新生成 —— 而「解密失败 + 用户随后改了任意一个设置」两者一叠加就会踩中。
 *
 * 代价：明文口令恰好以 `enc:v1:` 开头时不会被加密。该前缀是应用自己的密文标记，
 * 这种取值只可能是手改配置造成的，且这类字段本来就无法区分「明文」与「坏密文」。
 */
internal fun encryptStoredValue(plaintext: String): String =
    if (plaintext.startsWith(ENCRYPTED_VALUE_PREFIX)) {
        plaintext
    } else {
        ENCRYPTED_VALUE_PREFIX + EncryptionService.encrypt(plaintext.encodeToByteArray(), sensitiveEncryptionKey())
    }

/**
 * Decrypts a `enc:v1:…` stored value. Plaintext (hand-edited files) passes
 * through unchanged; a value that fails to decrypt is kept as-is + logged —
 * one bad field must not take down the whole config.
 */
internal fun decryptStoredValue(stored: String): String {
    if (!stored.startsWith(ENCRYPTED_VALUE_PREFIX)) return stored
    return try {
        EncryptionService.decrypt(
            stored.removePrefix(ENCRYPTED_VALUE_PREFIX),
            sensitiveEncryptionKey(),
        ).decodeToString()
    } catch (t: Throwable) {
        Log.warn("core.config", "failed to decrypt a value: $t")
        stored // keep the raw ciphertext rather than corrupting the field
    }
}

/**
 * Serializer for sensitive string properties: encrypts on encode, decrypts
 * on decode (see [decryptStoredValue] for the tolerant failure semantics).
 */
object EncryptedStringSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("mqclient.EncryptedString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(encryptStoredValue(value))
    }

    override fun deserialize(decoder: Decoder): String = decryptStoredValue(decoder.decodeString())
}
