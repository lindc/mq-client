package com.mqclient.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Filesystem-backed persistence for the app's two config files:
 *
 *   ~/.mqclient/
 *     ├── secret.key         Base64-encoded 32-byte AES-256 key
 *     ├── config.json        Plaintext — theme + AI panel settings
 *     └── connections.json   AES-GCM encrypted — list of [ConnectionInfo]
 *
 * Every operation swallows IO / serialization errors and falls back to a
 * safe default rather than throwing — the panel and the sidebar must still
 * come up if the user's disk is full or the key file got corrupted. Failures
 * are logged to stderr so they're at least visible.
 */
object ConfigStore {

    /** Compact encoding — used for `connections.json`, whose plaintext is
     *  encrypted anyway, so indentation there is pure ciphertext overhead. */
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Same settings, but pretty-printed — used for `config.json`, the one
     * file a user is expected to open and hand-edit (or diff after a
     * settings change). Derives from [json] so the two never drift apart
     * on the options that actually matter.
     */
    private val prettyJson = Json(from = json) { prettyPrint = true }

    /** `~/.mqclient/` — created lazily on first access. */
    fun configDir(): Path = Paths.get(System.getProperty("user.home"), ".mqclient")

    private fun ensureDir(): Path = Files.createDirectories(configDir())

    private fun secretKeyFile(): Path = ensureDir().resolve("secret.key")
    private fun configFile(): Path = ensureDir().resolve("config.json")
    private fun connectionsFile(): Path = ensureDir().resolve("connections.json")

    /**
     * Returns the AES key used for `connections.json`. Generates and persists
     * a fresh 32-byte key on first call, so the very first launch creates the
     * file and every subsequent launch reuses it.
     */
    fun ensureSecretKey(): javax.crypto.spec.SecretKeySpec {
        val file = secretKeyFile()
        if (!Files.exists(file)) {
            val bytes = Crypto.generateKeyBytes()
            Files.writeString(file, Base64.getEncoder().encodeToString(bytes))
            // Lock down permissions on POSIX so other users on the box can't read
            // the key. Best-effort — silently no-op on Windows.
            try {
                Files.setPosixFilePermissions(file, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                ))
            } catch (_: UnsupportedOperationException) {}
        }
        val b64 = Files.readString(file).trim()
        return Crypto.keyFromBytes(Base64.getDecoder().decode(b64))
    }

    /**
     * Reads [Config] from `config.json`. Missing or unparsable file yields
     * [Config] defaults — every field has a default in its data class, so
     * the UI never has to special-case a missing file.
     */
    fun loadConfig(): Config =
        try {
            val file = configFile()
            if (!Files.exists(file)) Config()
            else prettyJson.decodeFromString(Config.serializer(), Files.readString(file))
        } catch (t: Throwable) {
            System.err.println("[ConfigStore] failed to read config: $t")
            Config()
        }

    /**
     * Writes [config] to `config.json`, replacing whatever was there.
     *
     * Returns a [Result] instead of swallowing failures: config is the one
     * thing the user edits by hand in the settings dialog, so a write that
     * silently doesn't happen (disk full, file locked, read-only home) has
     * to be reportable — otherwise "保存" looks like it worked and the
     * settings are gone on next launch.
     *
     * Callers that genuinely don't care (the startup seed, the AI panel's
     * fire-and-forget updates) can just ignore the return value; failures
     * are still logged to stderr either way.
     *
     * Written pretty-printed (see [prettyJson]) so the file stays readable
     * and diff-able.
     */
    fun saveConfig(config: Config): Result<Unit> =
        runCatching {
            Files.writeString(
                configFile(),
                prettyJson.encodeToString(Config.serializer(), config),
            )
        }
            // Files.writeString hands back the Path; we only care whether it
            // threw, so normalise to Unit rather than leaking a Path.
            .map { Unit }
            .onFailure { t ->
                System.err.println("[ConfigStore] failed to write config: $t")
            }

    /**
     * Decrypts `connections.json` into a list. Returns an empty list if the
     * file is missing (the caller will then ask us to seed the empty
     * template), or if decryption fails for any reason — wrong key,
     * tampered ciphertext, etc.
     */
    fun loadConnections(): List<ConnectionInfo> =
        try {
            val file = connectionsFile()
            if (!Files.exists(file)) emptyList()
            else {
                val payload = Files.readString(file)
                if (payload.isBlank()) emptyList()
                else {
                    val plaintext = Crypto.decrypt(payload, ensureSecretKey()).toString(Charsets.UTF_8)
                    json.decodeFromString(ListSerializer(ConnectionInfo.serializer()), plaintext)
                }
            }
        } catch (t: Throwable) {
            System.err.println("[ConfigStore] failed to read connections: $t")
            emptyList()
        }

    fun saveConnections(connections: List<ConnectionInfo>) {
        try {
            val plaintext = json.encodeToString(
                ListSerializer(ConnectionInfo.serializer()),
                connections,
            ).toByteArray(Charsets.UTF_8)
            val payload = Crypto.encrypt(plaintext, ensureSecretKey())
            Files.writeString(connectionsFile(), payload)
        } catch (t: Throwable) {
            System.err.println("[ConfigStore] failed to write connections: $t")
        }
    }

    /**
     * Creates an empty `connections.json` (encrypted `[]`) if it isn't there
     * yet. Called from the startup flow so the file is ready before the
     * sidebar tries to display its contents.
     */
    fun ensureConnectionsFile() {
        if (!Files.exists(connectionsFile())) saveConnections(emptyList())
    }

    /**
     * First-launch seed: when `config.json` doesn't exist yet, write the
     * default [Config] to disk so the file is always present from the very
     * first run. Subsequent runs no-op.
     *
     * Without this the user would only see defaults in memory; the settings
     * dialog could still edit them, but anything that reads the file
     * directly (and any "did the user touch this?" detection) would have to
     * special-case the missing-file state.
     */
    fun ensureConfigFile() {
        if (!Files.exists(configFile())) saveConfig(Config())
    }

    /**
     * One-shot init for app startup. Generates the AES key, seeds the
     * connections file, and writes default config if missing. Cheap to
     * call repeatedly — each step no-ops when its target already exists.
     */
    fun initOnStartup() {
        ensureSecretKey()
        ensureConnectionsFile()
        ensureConfigFile()
    }
}