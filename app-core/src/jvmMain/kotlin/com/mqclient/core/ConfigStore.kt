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

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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
            else json.decodeFromString(Config.serializer(), Files.readString(file))
        } catch (t: Throwable) {
            System.err.println("[ConfigStore] failed to read config: $t")
            Config()
        }

    fun saveConfig(config: Config) {
        try {
            Files.writeString(
                configFile(),
                json.encodeToString(Config.serializer(), config),
            )
        } catch (t: Throwable) {
            System.err.println("[ConfigStore] failed to write config: $t")
        }
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
     * One-shot init for app startup. Generates the AES key and seeds the
     * connections file if missing. Cheap to call repeatedly — each step
     * no-ops when its target already exists.
     */
    fun initOnStartup() {
        ensureSecretKey()
        ensureConnectionsFile()
    }
}