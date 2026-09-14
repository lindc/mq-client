package com.mqclient.core

import com.mqclient.core.log.Log
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
 *     ├── secret.key       32 random bytes, Base64-encoded (AES-256 key)
 *     ├── config.json      pretty JSON: general / theme / ai / connections /
 *                          metadata, sensitive string fields stored as
 *                          `enc:v1:<iv>:<ct+tag>` ciphertext
 *     └── config.json.bak  上一版 config.json，每次 [save] 成功写入前留一份
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
 * and log via the shared [Log] — the UI must still boot when the key is lost or the
 * file is corrupted; writes report failures through [Result] so the settings
 * dialog can surface "save failed" instead of silently losing edits.
 */
object ConfigManager {

    private const val CONFIG_FILE_NAME = "config.json"
    private const val BACKUP_FILE_NAME = "config.json.bak"
    private const val SECRET_KEY_FILE_NAME = "secret.key"
    private const val LEGACY_CONNECTIONS_FILE_NAME = "connections.json"
    private const val TMP_SUFFIX = ".tmp"

    /** 统一日志 tag；与上报错误时的 source（`core.config`）同一命名。 */
    private const val LOG_TAG = "core.config"

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
    private fun backupFile(): Path = configDir().resolve(BACKUP_FILE_NAME)
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
        migrateAiConnectionsIfNeeded()
    }

    /**
     * AI 连接模型的一次性升级：旧 `ai.providerConfigs`（per-provider 单槽）折叠成
     * `ai.connections`（多条连接）。已迁移过则零开销；配置读不出来时跳过（下次启动
     * 再试）。迁移规则本身在 [AiConnectionRepository]（AI 域），这里只负责把它挂到
     * 启动路径上。
     */
    private fun migrateAiConnectionsIfNeeded() {
        val current = loadOrNull() ?: return
        val migrated = AiConnectionRepository.migrateIfNeeded(current.ai)
        if (migrated !== current.ai) {
            save(current.copy(ai = migrated)).onFailure { error ->
                Log.warn(LOG_TAG, "failed to persist migrated AI connections (retry next launch): $error")
            }
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
     * Reads and decrypts `config.json` into a [Config], **把「文件不存在」和
     * 「文件存在但读不出来」压成同一个结果**：两者都得到 [Config] 默认值。
     *
     * 只适合启动首帧这种「读不到就用默认值、之后立刻会被真实值覆盖」的场合。
     * 凡是「读-改-写」路径（读出来 → copy 改一个字段 → 写回）都必须改用
     * [loadOrNull]：默认值里其它配置段是空的，拿它去 copy 再落盘会把用户的
     * 连接列表整段抹掉。
     *
     * 单个敏感字段解密失败仍保留原文 + 日志（见 [decryptTree]），
     * 一个坏字段不会拖垮整个配置。
     */
    fun load(): Config = loadOrNull() ?: Config()

    /**
     * [load] 的「读不出来就明说」版本：文件缺失或为空属于首次启动，返回
     * [Config] 默认值；文件在但读不出来（损坏 / 被占用 / 整体解密失败）返回
     * `null`，调用方据此放弃写入，不要拿默认值覆盖磁盘上的真实配置。
     */
    fun loadOrNull(): Config? {
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
            Log.error(LOG_TAG, "failed to read config: $t")
            null
        }
    }

    /* -------------------------------- saving ------------------------------- */

    /**
     * Encrypts [config]'s sensitive fields and writes it to `config.json`
     * through a temp file + atomic rename, so a crash mid-write can never
     * leave a half-written file behind.
     *
     * 写入前先把当前 `config.json` 复制成 `config.json.bak`：写入是原子的、
     * 不可撤销的，而 [load] 的容错会让一次读失败变成「用默认值整段覆盖」，
     * 留一份上一版是这条错误链上唯一的后悔药。
     */
    fun save(config: Config): Result<Unit> =
        runCatching {
            val key = ensureSecretKey()
            val element = json.encodeToJsonElement(Config.serializer(), config)
            val encrypted = encryptTree(element, key)
            backupCurrentConfig()
            writeFileAtomically(configFile(), prettyPrintJson(encrypted))
        }.onFailure { t ->
            Log.error(LOG_TAG, "failed to write config: $t")
        }

    /**
     * 把当前 `config.json` 复制成 `config.json.bak`（只保留一份，每次保存覆盖）。
     * 备份失败不能连累保存本身 —— 它只是保险，缺了它写入依然是正确的。
     */
    private fun backupCurrentConfig() {
        val current = configFile()
        if (!fs.exists(current)) return
        try {
            fs.copy(current, backupFile())
        } catch (t: Throwable) {
            Log.warn(LOG_TAG, "failed to back up config.json: $t")
        }
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
            Log.warn(LOG_TAG, "legacy connections.json unreadable: $t")
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
        // 读-改-写：读不出来就不落盘，宁可把 legacy 文件留到下次启动再迁。
        val current = loadOrNull() ?: return
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
                    Log.warn(LOG_TAG, "failed to decrypt a value: $t")
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
