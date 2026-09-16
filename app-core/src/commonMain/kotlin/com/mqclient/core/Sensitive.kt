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

/** Encrypts [plaintext] into a `enc:v1:…` stored value. */
internal fun encryptStoredValue(plaintext: String): String =
    ENCRYPTED_VALUE_PREFIX + EncryptionService.encrypt(plaintext.encodeToByteArray(), sensitiveEncryptionKey())

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
