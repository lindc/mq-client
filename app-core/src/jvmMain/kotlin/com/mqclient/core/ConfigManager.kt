package com.mqclient.core

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

/**
 * Owns the on-disk configuration for the whole app. After first launch the
 * config directory contains exactly two files:
 *
 *   ~/.mqclient/
 *     ├── secret.key   32 random bytes, Base64-encoded (AES-256 key)
 *     └── config.json  pretty JSON: general / theme / ai / connections /
 *                      metadata, sensitive string fields stored as
 *                      `enc:v1:<iv>:<ct+tag>` ciphertext
 *
 * Sensitive-field handling: ConfigManager walks the serialized JSON tree and
 * encrypts/decrypts every **string** value under a key whose (lowercased)
 * name contains one of [SENSITIVE_NAME_KEYWORDS] — password, apiKey,
 * accessToken, secret, token, … Name matching works across any model shape —
 * including the per-service connection payloads under `connections`, whose
 * concrete fields the middleware modules decide — without reflection, which
 * is unavailable in commonMain. [Sensitive] annotations mark the same intent
 * in the schema for a future serializer-driven upgrade.
 *
 * Failure policy mirrors the old ConfigStore: reads degrade to safe defaults
 * and log to stderr — the UI must still boot when the key is lost or the
 * file is corrupted; writes report failures through [Result] so the settings
 * dialog can surface "save failed" instead of silently losing edits.
 */
object ConfigManager {

    private const val CONFIG_FILE_NAME = "config.json"
    private const val SECRET_KEY_FILE_NAME = "secret.key"
    private const val LEGACY_CONNECTIONS_FILE_NAME = "connections.json"
    private const val TMP_SUFFIX = ".tmp"

    /** Prefix distinguishing encrypted values inside config.json. */
    const val ENCRYPTED_VALUE_PREFIX = "enc:v1:"

    /**
     * Field-name keywords that mark a JSON string value as sensitive.
     * Matching is a case-insensitive substring test on the property name.
     * Conservative on purpose: `token` alone already covers accessToken and
     * refreshToken, while names like prompt / url / model stay plaintext.
     */
    val SENSITIVE_NAME_KEYWORDS: List<String> = listOf(
        "password", "passwd", "pwd",
        "apikey", "api_key", "accesstoken", "token",
        "secret", "privatekey", "credential",
    )

    private val fs: FileSystem = FileSystem.SYSTEM

    /** Pretty JSON for config.json (hand-editable, diff-able); tolerant of unknown keys. */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Compact JSON for decoding legacy whole-file-encrypted payloads. */
    private val legacyJson = Json { ignoreUnknownKeys = true }

    /* ------------------------------ locations ------------------------------ */

    /**
     * Config directory. Points at `~/.mqclient` unless the system property
     * `mqclient.config.dir` is set — a test hook that lets probes and suites
     * run against a throwaway directory.
     */
    fun configDir(): Path {
        val override = System.getProperty("mqclient.config.dir")
        val root = override ?: System.getProperty("user.home") + File.separator + ".mqclient"
        return File(root).toOkioPath()
    }

    private fun configFile(): Path = configDir().resolve(CONFIG_FILE_NAME)
    private fun secretKeyFile(): Path = configDir().resolve(SECRET_KEY_FILE_NAME)
    private fun legacyConnectionsFile(): Path = configDir().resolve(LEGACY_CONNECTIONS_FILE_NAME)

    /* ------------------------------- startup ------------------------------- */

    /**
     * First-launch + migration entry point (idempotent):
     *  1. generate `secret.key` when missing;
     *  2. seed a default `config.json` when missing;
     *  3. fold a leftover legacy encrypted `connections.json` into the single
     *     file (merging by connection id) and delete it — on every launch
     *     until it's gone, so the config dir ends up with exactly two files.
     */
    fun initOnStartup() {
        ensureSecretKey()
        if (!fs.exists(configFile())) {
            // First launch: seed defaults; fold any legacy list into the same
            // write. A null read means "absent or unreadable" — in the latter
            // case the legacy file is kept as the user's only copy.
            val legacy = readLegacyConnections()
            if (save(Config(connections = legacy ?: emptyList())).isSuccess && legacy != null) {
                fs.delete(legacyConnectionsFile())
            }
        } else {
            importLegacyConnectionsIfAny()
        }
    }

    /** Returns the secret key bytes, generating + persisting them on first use. */
    fun ensureSecretKey(): ByteArray {
        val path = secretKeyFile()
        if (!fs.exists(path)) {
            val key = EncryptionService.generateKeyBytes()
            writeFileAtomically(path, Base64.getEncoder().encodeToString(key))
            restrictPermissions(path)
        }
        return Base64.getDecoder().decode(fs.read(secretKeyFile()) { readUtf8() }.trim())
    }

    /* -------------------------------- loading ------------------------------ */

    /**
     * Reads and decrypts `config.json` into a [Config]. Missing or unreadable
     * file → [Config] defaults; an undecryptable sensitive value (wrong key,
     * tampered ciphertext) stays as its raw text with a stderr log, so one
     * bad field can't take down the whole config.
     */
    fun load(): Config {
        val path = configFile()
        if (!fs.exists(path)) return Config()
        return try {
            val key = ensureSecretKey()
            val text = fs.read(path) { readUtf8() }
            if (text.isBlank()) return Config()
            val plain = decryptTree(json.parseToJsonElement(text), key)
            // JsonElement.toString() emits exact compact JSON; decode from that.
            json.decodeFromString(Config.serializer(), plain.toString())
        } catch (t: Throwable) {
            System.err.println("[ConfigManager] failed to read config: $t")
            Config()
        }
    }

    /* -------------------------------- saving ------------------------------- */

    /**
     * Encrypts [config]'s sensitive fields and writes it to `config.json`
     * through a temp file + atomic rename, so a crash mid-write can never
     * leave a half-written file behind.
     */
    fun save(config: Config): Result<Unit> =
        runCatching {
            val key = ensureSecretKey()
            val element = json.encodeToJsonElement(Config.serializer(), config)
            val encrypted = encryptTree(element, key)
            writeFileAtomically(configFile(), prettyPrintJson(encrypted))
        }.onFailure { t ->
            System.err.println("[ConfigManager] failed to write config: $t")
        }

    /* ------------------------- legacy connections.json --------------------- */

    /**
     * Reads the old pre-migration `connections.json` (whole list as one
     * AES-GCM blob). Returns `null` when the file is absent, blank or
     * undecryptable — callers must then keep the file in place, since it may
     * be the user's only copy of those connections.
     */
    private fun readLegacyConnections(): List<ConnectionInfo>? {
        val path = legacyConnectionsFile()
        if (!fs.exists(path)) return null
        return try {
            val key = ensureSecretKey()
            val payload = fs.read(path) { readUtf8() }
            if (payload.isBlank()) return null
            val plaintext = EncryptionService.decrypt(payload, key)
            legacyJson.decodeFromString(
                ListSerializer(ConnectionInfo.serializer()),
                plaintext.decodeToString(),
            )
        } catch (t: Throwable) {
            System.err.println("[ConfigManager] legacy connections.json unreadable: $t")
            null
        }
    }

    /**
     * One-time migration for configs that already have a `config.json`:
     * folds a leftover legacy `connections.json` into the `connections`
     * section (legacy entries whose id is already present are skipped) and
     * deletes the old file. Never deletes on a failed decrypt.
     */
    private fun importLegacyConnectionsIfAny() {
        if (!fs.exists(legacyConnectionsFile())) return
        val legacy = readLegacyConnections() ?: return // unreadable — keep the file
        val current = load()
        val existingIds = current.connections.mapTo(mutableSetOf()) { it.id }
        val fresh = legacy.filter { it.id !in existingIds }
        val merged = current.copy(connections = current.connections + fresh)
        if (save(merged).isSuccess) {
            fs.delete(legacyConnectionsFile())
        }
    }

    /* ------------------------ sensitive-field transform -------------------- */

    private fun isSensitiveKey(name: String): Boolean {
        val lower = name.lowercase()
        return SENSITIVE_NAME_KEYWORDS.any { lower.contains(it) }
    }

    /** True for string primitives only — numbers / booleans pass through untouched. */
    private fun JsonPrimitive.isStringValue(): Boolean = contentOrNull != null

    private fun encryptTree(element: JsonElement, key: ByteArray): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (name, value) ->
                val plain = if (value is JsonPrimitive) value.contentOrNull else null
                if (plain != null && isSensitiveKey(name)) {
                    val ct = EncryptionService.encrypt(plain.toByteArray(Charsets.UTF_8), key)
                    JsonPrimitive(ENCRYPTED_VALUE_PREFIX + ct)
                } else {
                    encryptTree(value, key)
                }
            },
        )
        is JsonArray -> JsonArray(element.map { encryptTree(it, key) })
        else -> element
    }

    private fun decryptTree(element: JsonElement, key: ByteArray): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> decryptTree(value, key) })
        is JsonArray -> JsonArray(element.map { decryptTree(it, key) })
        is JsonPrimitive -> {
            val content = element.contentOrNull
            if (content != null && content.startsWith(ENCRYPTED_VALUE_PREFIX)) {
                try {
                    JsonPrimitive(
                        EncryptionService.decrypt(
                            content.removePrefix(ENCRYPTED_VALUE_PREFIX),
                            key,
                        ).decodeToString(),
                    )
                } catch (t: Throwable) {
                    System.err.println("[ConfigManager] failed to decrypt a value: $t")
                    element // keep the raw ciphertext rather than corrupting the field
                }
            } else {
                element
            }
        }
    }

    /* ----------------------- sensitive-value transform -------------------- */

    /**
     * Minimal JSON pretty-printer (2-space indent) used to write config.json
     * with encrypted values in place — avoids depending on the internal
     * JsonElement serializer while keeping the file hand-editable.
     */
    private fun prettyPrintJson(element: JsonElement): String = buildString {
        appendElement(element, 0)
    }

    private fun StringBuilder.appendElement(element: JsonElement, indent: Int) {
        when (element) {
            is JsonObject -> {
                append('{')
                var first = true
                element.forEach { (key, value) ->
                    if (!first) append(',')
                    first = false
                    append('\n')
                    appendIndent(indent + 1)
                    append('"').append(escapeJson(key)).append("\": ")
                    appendElement(value, indent + 1)
                }
                if (!first) {
                    append('\n')
                    appendIndent(indent)
                }
                append('}')
            }
            is JsonArray -> {
                append('[')
                var first = true
                element.forEach { value ->
                    if (!first) append(',')
                    first = false
                    append('\n')
                    appendIndent(indent + 1)
                    appendElement(value, indent + 1)
                }
                if (!first) {
                    append('\n')
                    appendIndent(indent)
                }
                append(']')
            }
            is JsonPrimitive -> {
                val content = element.contentOrNull
                if (content != null) append('"').append(escapeJson(content)).append('"')
                else append(element.toString()) // numbers / booleans / null — JsonNull included
            }
        }
    }

    private fun StringBuilder.appendIndent(level: Int) {
        repeat(level) { append("  ") }
    }

    /** Escapes a raw string for embedding inside a JSON string literal. */
    private fun escapeJson(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    /* --------------------------- atomic file writes ------------------------ */

    /**
     * Writes [text] to a sibling `*.tmp` file, then atomically moves it over
     * [target]. Falls back to delete-then-move when the filesystem refuses
     * the atomic rename (rare on Windows).
     */
    private fun writeFileAtomically(target: Path, text: String) {
        val parent = target.parent ?: error("cannot write $target: no parent directory")
        fs.createDirectories(parent)
        val tmp = parent.resolve(target.name + TMP_SUFFIX)
        if (fs.exists(tmp)) fs.delete(tmp)
        fs.write(tmp) { writeUtf8(text) }
        try {
            fs.atomicMove(tmp, target)
        } catch (t: Throwable) {
            if (fs.exists(target)) fs.delete(target)
            fs.atomicMove(tmp, target)
        }
    }

    /** Best-effort 0600 on POSIX so other users on the box can't read the key. */
    private fun restrictPermissions(path: Path) {
        try {
            Files.setPosixFilePermissions(
                path.toNioPath(),
                java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ),
            )
        } catch (_: UnsupportedOperationException) {
            // Windows — no POSIX permissions; skip silently.
        }
    }
}
