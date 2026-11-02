package com.mqclient.core

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Backward-compatible facade over [ConfigManager].
 *
 * The app moved to a single `config.toml` (connections included, sensitive
 * fields encrypted in place) plus `secret.key` — nothing else in the config
 * directory. This object keeps the pre-migration API surface (used by
 * app-ui and ai-panel) intact so no caller outside core had to change:
 *
 *   - [initOnStartup] / [loadConfig] / [saveConfig] — delegated 1:1;
 *   - [loadConnections] / [saveConnections] — read / rewrite the
 *     `connections` section of the same single file.
 *
 * Failure semantics: reads swallow errors and return safe defaults (logged via
 * the shared Log); **both writes** ([saveConfig] / [saveConnections]) surface failures
 * via [Result], so no caller has to guess whether an edit landed on disk.
 */
object ConfigStore {

    /** `~/.mqclient/` (or the `mqclient.config.dir` override), as a java.nio path. */
    fun configDir(): Path = Paths.get(ConfigManager.configDir().toString())

    private fun configFile(): Path = configDir().resolve("config.toml")

    /**
     * First-launch seed + legacy migration. Generates `secret.key` when
     * missing, writes a default `config.toml` on first run, and folds any
     * leftover legacy `connections.json` into the single file.
     */
    fun initOnStartup() {
        ConfigManager.initOnStartup()
    }

    /**
     * Reads the app config from `config.toml`, decrypting sensitive fields.
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
     * Encrypts sensitive fields and writes [config] to `config.toml`
     * (temp file + atomic rename). Returns a [Result] so callers can
     * surface a failed "保存" instead of silently dropping edits.
     */
    fun saveConfig(config: Config): Result<Unit> = ConfigManager.save(config)

    /**
     * 原子的读-改-写（[ConfigManager.edit]）：在锁内读 → 交给 [transform] 改 → 落盘。
     *
     * 「读一份配置、改一个段、再写回」的调用方（[saveConnections]、
     * AI 模块的连接仓库、AI 面板的设置项）都必须走这里，而不是
     * [loadConfigOrNull] + [saveConfig] 两步 —— 那两步之间是另一个写入者的机会窗口，
     * 被插队就意味着整段配置被后写的那次覆盖掉。
     */
    fun editConfig(transform: (Config) -> Result<Config>): Result<Unit> = ConfigManager.edit(transform)

    /**
     * 读 `connections` 段。
     *
     * 失败不再「假装没有连接」：[loadConfig] 在读不出来时会静默给出默认值，拿它当结果
     * 用会让界面上的连接全部凭空消失（用户会以为连接被删了）。这里用 [loadConfigOrNull]
     * 把两种情况分开：
     *  - 文件不存在（首次启动）→ 空列表，正常；
     *  - 文件在但读不出来（损坏 / 密钥丢失）→ `failure`，同时通过 [CoreModule.reportError]
     *    上报，让调用方和用户都能知道。
     */
    fun loadConnections(): Result<List<ConnectionInfo>> {
        val config = loadConfigOrNull()
        if (config != null) return Result.success(config.connections)

        val failure = IllegalStateException("config.toml exists but could not be read (the connections are safe — they just were not loaded this time)")
        CoreModule.reportError(
            message = failure.message ?: "Failed to read the connection list",
            source = "core.config",
            error = failure,
        )
        return Result.failure(failure)
    }

    /**
     * Replaces the `connections` section and rewrites config.toml, preserving
     * every other section as it currently sits on disk.
     */
    fun saveConnections(connections: List<ConnectionInfo>): Result<Unit> =
        // 读-改-写走 [editConfig]：配置读不出来时它不会调用 transform，直接以失败
        // 返回 —— 不写、也不假装成功（[loadConfig] 的默认值里其它段是空的）。
        editConfig { current -> Result.success(current.copy(connections = connections)) }

    /**
     * Returns the AES key used for the encrypted fields, generating and
     * persisting `secret.key` on first use.
     */
    fun ensureSecretKey(): javax.crypto.spec.SecretKeySpec {
        val bytes = ConfigManager.ensureSecretKey()
        return javax.crypto.spec.SecretKeySpec(bytes, "AES")
    }
}
