package com.mqclient.core

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM actual of [EncryptionService] — AES/GCM/NoPadding, 256-bit key.
 *
 * The output format intentionally matches the format the app used before
 * the single-file migration (`Base64(iv):Base64(ct+tag)`), so encrypted
 * values written by either generation decrypt under the same [secret.key].
 */
actual object EncryptionService {

    private const val KEY_SIZE_BYTES = 32 // AES-256
    private const val GCM_IV_BYTES = 12 // GCM's spec-recommended nonce length
    private const val GCM_TAG_BITS = 128

    actual fun generateKeyBytes(): ByteArray =
        ByteArray(KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }

    actual fun encrypt(plaintext: ByteArray, key: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        return "${Base64.getEncoder().encodeToString(iv)}:" +
            Base64.getEncoder().encodeToString(ct)
    }

    actual fun decrypt(payload: String, key: ByteArray): ByteArray {
        val parts = payload.split(":", limit = 2)
        require(parts.size == 2) { "Encrypted payload missing IV separator" }
        val iv = Base64.getDecoder().decode(parts[0])
        val ct = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}
