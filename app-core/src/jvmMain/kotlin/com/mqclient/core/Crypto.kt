package com.mqclient.core

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM (256-bit) helpers used to encrypt the on-disk connections list.
 *
 * Output format is `Base64(iv):Base64(ciphertext+tag)` — the 12-byte IV is
 * regenerated for every write, the 16-byte GCM authentication tag rides at
 * the tail of the ciphertext so `decrypt` fails loudly on tampering instead
 * of silently returning garbage.
 */
object Crypto {

    /** AES key size in bits — fixed at 256, no need to negotiate. */
    private const val KEY_SIZE_BITS = 256

    /** GCM IV length in bits — 12 bytes is the spec-recommended value. */
    private const val GCM_IV_BITS = 96

    /** Tag length in bits — 128 is the GCM default. */
    private const val GCM_TAG_BITS = 128

    /** Fresh 32 random bytes, suitable as an AES-256 key. */
    fun generateKeyBytes(): ByteArray =
        ByteArray(KEY_SIZE_BITS / 8).also { SecureRandom().nextBytes(it) }

    /** Convenience: build a [SecretKeySpec] from raw key bytes. */
    fun keyFromBytes(bytes: ByteArray): SecretKeySpec =
        SecretKeySpec(bytes, "AES")

    /**
     * Encrypts [plaintext] under [key]. Output is `Base64(iv) : Base64(ct)` —
     * the colon is a safe separator because Base64 output only uses
     * `[A-Za-z0-9+/=]`, never a colon.
     */
    fun encrypt(plaintext: ByteArray, key: SecretKeySpec): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // 12 bytes, GCM's spec-recommended length
        val ct = cipher.doFinal(plaintext)
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val ctB64 = Base64.getEncoder().encodeToString(ct)
        return "$ivB64:$ctB64"
    }

    /**
     * Inverse of [encrypt]. Throws on any tag mismatch — callers should
     * treat that as "file is corrupted or wrong key, give up".
     */
    fun decrypt(payload: String, key: SecretKeySpec): ByteArray {
        val parts = payload.split(":", limit = 2)
        require(parts.size == 2) { "Encrypted payload missing IV separator" }
        val iv = Base64.getDecoder().decode(parts[0])
        val ct = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ct)
    }
}