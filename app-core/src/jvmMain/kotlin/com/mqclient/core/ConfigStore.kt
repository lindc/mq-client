package com.mqclient.core

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Backward-compatible facade over [ConfigManager].
 *
 * The app moved to a single `config.json` (connections included, sensitive
 * fields encrypted in place) plus `secret.key` — nothing else in the config
 * directory. This object keeps the pre-migration API surface (used by
 * app-ui and ai-panel) intact so no caller outside core had to change:
 *
 *   - [initOnStartup] / [loadConfig] / [saveConfig] — delegated 1:1;
 *   - [loadConnections] / [saveConnections] — read / rewrite the
 *     `connections` section of the same single file.
 *
 * Failure semantics match the old implementation: reads swallow errors and
 * return safe defaults (logging to stderr); writes surface failures via
 * [Result] where the old API did.
 */
object ConfigStore {

    /** `~/.mqclient/` (or the `mqclient.config.dir` override), as a java.nio path. */
    fun configDir(): Path = Paths.get(ConfigManager.configDir().toString())

    private fun configFile(): Path = configDir().resolve("config.json")

    /**
     * First-launch seed + legacy migration. Generates `secret.key` when
     * missing, writes a default `config.json` on first run, and folds any
     * leftover legacy `connections.json` into the single file.
     */
    fun initOnStartup() {
        ConfigManager.initOnStartup()
    }

    /**
     * Reads the app config from `config.json`, decrypting sensitive fields.
     * Missing or unparsable file yields [Config] defaults.
     */
    fun loadConfig(): Config = ConfigManager.load()

    /**
     * [loadConfig] 的「读不出来就明说」版本：配置文件在但读不出来时返回
     * `null`（[ConfigManager.loadOrNull]）。
     *
     * 凡是「读-改-写」路径都必须用它并在 `null` 时放弃写入 —— [loadConfig] 的
     * 默认值里其它配置段是空的，拿它去 copy 再落盘会抹掉用户的连接列表。
     */
    fun loadConfigOrNull(): Config? = ConfigManager.loadOrNull()

    /**
     * Encrypts sensitive fields and writes [config] to `config.json`
     * (temp file + atomic rename). Returns a [Result] so callers can
     * surface a failed "保存" instead of silently dropping edits.
     */
    fun saveConfig(config: Config): Result<Unit> = ConfigManager.save(config)

    /** Returns the `connections` section of the current config; [] when unreadable. */
    fun loadConnections(): List<ConnectionInfo> =
        try {
            loadConfig().connections
        } catch (t: Throwable) {
            System.err.println("[ConfigStore] failed to read connections: $t")
            emptyList()
        }

    /**
     * Replaces the `connections` section and rewrites config.json, preserving
     * every other section as it currently sits on disk.
     */
    fun saveConnections(connections: List<ConnectionInfo>) {
        // 读-改-写：配置读不出来时 [loadConfig] 会静默给出默认值，拿它写回会
        // 抹掉配置里的其它段，所以这里必须走 loadConfigOrNull 并在 null 时放弃。
        val current = loadConfigOrNull()
        if (current == null) {
            System.err.println("[ConfigStore] refused to write connections: config.json unreadable")
            return
        }
        saveConfig(current.copy(connections = connections))
    }

    /**
     * Returns the AES key used for the encrypted fields, generating and
     * persisting `secret.key` on first use.
     */
    fun ensureSecretKey(): javax.crypto.spec.SecretKeySpec {
        val bytes = ConfigManager.ensureSecretKey()
        return javax.crypto.spec.SecretKeySpec(bytes, "AES")
    }
}
