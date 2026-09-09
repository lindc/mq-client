package com.mqclient.core

/**
 * Cross-platform AES-256-GCM encryption facade used by [ConfigManager].
 *
 * Wire format: `Base64(iv) : Base64(ciphertext + GCM tag)` — the 12-byte IV
 * is regenerated per encrypt call, the 128-bit tag rides at the tail of the
 * ciphertext so decrypt fails loudly on tampering or a wrong key.
 *
 * The key is the raw 32 bytes read from `secret.key` (Base64 on disk).
 *
 * JVM actual: [EncryptionServiceJvm] (javax.crypto).
 */
expect object EncryptionService {

    /** Generates 32 fresh random bytes suitable as an AES-256 key. */
    fun generateKeyBytes(): ByteArray

    /** Encrypts [plaintext] under [key]; output is `Base64(iv):Base64(ct+tag)`. */
    fun encrypt(plaintext: ByteArray, key: ByteArray): String

    /** Inverse of [encrypt]; throws (AEADBadTagException) on tampering / wrong key. */
    fun decrypt(payload: String, key: ByteArray): ByteArray
}
