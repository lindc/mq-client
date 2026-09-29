package com.mqclient.core

import com.akuleshov7.ktoml.Toml
import com.mqclient.core.log.Log
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

/**
 * Owns the on-disk configuration for the whole app. After first launch the
 * config directory contains exactly two files:
 *
 *   ~/.mqclient/
 *     ├── secret.key       32 random bytes, Base64-encoded (AES-256 key)
 *     ├── config.toml      pretty TOML: general / theme / ai / connections /
 *                          metadata, sensitive string fields stored as
 *                          `enc:v1:<iv>:<ct+tag>` ciphertext
 *     └── config.toml.bak  上一版 config.toml，每次 [save] 成功写入前留一份
 *
 * Sensitive-field handling is serializer-driven: fields marked
 * `@Serializable(with = EncryptedStringSerializer::class)` (and the
 * [ConnectionInfo.options] values whose key matches
 * [SENSITIVE_NAME_KEYWORDS]) encrypt on encode / decrypt on decode —
 * [save] and [loadOrNull] are single [Toml] codec calls with no tree
 * walking. Sensitive values on disk carry the `enc:v1:` prefix
 * ([ENCRYPTED_VALUE_PREFIX]).
 *
 * Failure policy mirrors the old ConfigStore: reads degrade to safe defaults
 * and log via the shared [Log] — the UI must still boot when the key is lost or the
 * file is corrupted; writes report failures through [Result] so the settings
 * dialog can surface "save failed" instead of silently losing edits.
 *
 * Concurrency: the whole directory is guarded by one process-wide lock (see
 * [LOCK]); every read-modify-write goes through [edit], never through
 * [loadOrNull] + [save] as two separate steps.
 */
object ConfigManager {

    private const val CONFIG_FILE_NAME = "config.toml"
    private const val BACKUP_FILE_NAME = "config.toml.bak"
    private const val SECRET_KEY_FILE_NAME = "secret.key"
    private const val LEGACY_CONNECTIONS_FILE_NAME = "connections.json"
    private const val TMP_SUFFIX = ".tmp"

    /** 统一日志 tag；与上报错误时的 source（`core.config`）同一命名。 */
    private const val LOG_TAG = "core.config"

    /**
     * 整个配置目录的进程内锁。
     *
     * 必须只有一把：`config.toml` 是**一份**文件，`connections` 与 `ai` 都在里面，
     * 而写入方（[ConnectionStore]、AI 模块自己的连接仓库、设置对话框）写的都是
     * 「读整份 → 改一段 → 写回」。两把锁各自管住自己的一步、管不住两步之间的时间窗，
     * 那个窗口里另一个写入者插进来就会被后写的那次整段覆盖 —— 丢的不是某个字段，
     * 是一整段连接列表。
     *
     * 读-改-写统一走 [edit]，它把读、改、写三段锁在同一个临界区里。
     * Java 监视器可重入，所以 [save] 内部再调 [ensureSecretKey] 不会自锁。
     */
    private val LOCK = Any()

    /**
     * Field-name keywords that mark a string value as sensitive. Matching is
     * a case-insensitive substring test on the property name. Conservative on
     * purpose: `token` alone already covers accessToken and refreshToken,
     * while names like prompt / url / model stay plaintext. Consumed by
     * [SensitiveOptionsSerializer] — annotated fields carry their serializer
     * directly.
     */
    val SENSITIVE_NAME_KEYWORDS: List<String> = listOf(
        "password", "passwd", "pwd",
        "apikey", "api_key", "accesstoken", "token",
        "secret", "privatekey", "credential",
    )

    private val fs: FileSystem = FileSystem.SYSTEM

    /**
     * JSON 解码器，只用于旧格式文件（`connections.json` 整体加密块解出后的
     * 列表、迁移期的旧 `config.json`）；现行磁盘格式是 TOML。
     */
    private val legacyJson = Json { ignoreUnknownKeys = true }

    /** TOML 磁盘格式编解码（config.toml）。0.7.x 里 [Toml] 是类，用默认配置实例。 */
    private val toml = Toml.Default

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
     *  2. seed a default `config.toml` when missing;
     *  3. fold a leftover legacy encrypted `connections.json` into the single
     *     file (merging by connection id) and delete it — on every launch
     *     until it's gone, so the config dir ends up with exactly two files.
     */
    fun initOnStartup() {
        synchronized(LOCK) {
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
            migrateModuleSections()
        }
    }

    /**
     * 让各模块迁移自己在 config.toml 里的段（SPI：`ConfigMigration`）。
     *
     * 模块侧是**纯函数**（只改 [Config]，不落盘、不发事件、不碰数据库 —— 此刻
     * `CoreModule` / `DatabaseManager` 都还没初始化）；写盘由这里统一做，和本类的
     * 读-改-写共享同一个临界区，所以模块之间、以及模块与其它写入者之间都不会互相覆盖。
     *
     * 只有某个模块返回了**新对象**（引用变了）才保存，一个都没改动时一个字节都不写；
     * 配置读不出来时整块跳过（下次启动再试）—— 绝不拿默认值覆盖磁盘上的真实配置。
     */
    private fun migrateModuleSections() {
        val current = loadOrNull() ?: return
        val migrated = ModuleRegistry.runConfigMigrations(current)
        if (migrated === current) return
        save(migrated).onFailure { error ->
            Log.warn(LOG_TAG, "failed to persist module config migrations (retry next launch): $error")
        }
    }

    /**
     * Returns the secret key bytes, generating + persisting them on first use.
     *
     * 「不存在就生成」和「读回来」必须在同一个临界区里：拆开的话两个线程会各生成
     * 一把密钥、后写的胜出，而先写那把已经用它加密过的内容 —— 那些内容从此再也解
     * 不开（配置永久损坏，只能删掉 config.toml 重来）。
     *
     * 生成/首读后**缓存内存**：一次 save 里每个敏感字段都会走一遍这里，逐字段读盘
     * 纯属浪费。代价是密钥文件被外部替换后进程内不感知 —— 密钥本就是一次性的，
     * 运行中换文件属于损坏场景（重启即恢复一致）。
     */
    @Volatile
    private var cachedSecretKey: ByteArray? = null

    fun ensureSecretKey(): ByteArray = synchronized(LOCK) {
        cachedSecretKey?.let { return it }
        val path = secretKeyFile()
        if (!fs.exists(path)) {
            val key = EncryptionService.generateKeyBytes()
            writeFileAtomically(path, Base64.getEncoder().encodeToString(key))
            restrictPermissions(path)
        }
        Base64.getDecoder().decode(fs.read(path) { readUtf8() }.trim()).also { cachedSecretKey = it }
    }

    /* -------------------------------- loading ------------------------------ */

    /**
     * Reads and decrypts `config.toml` into a [Config], **把「文件不存在」和
     * 「文件存在但读不出来」压成同一个结果**：两者都得到 [Config] 默认值。
     *
     * 只适合启动首帧这种「读不到就用默认值、之后立刻会被真实值覆盖」的场合。
     * 凡是「读-改-写」路径（读出来 → copy 改一个字段 → 写回）都必须走 [edit]（原子），
     * 或至少用 [loadOrNull] 拿到真实内容：默认值里其它配置段是空的，拿它去 copy
     * 再落盘会把用户的连接列表整段抹掉。
     *
     * 单个敏感字段解密失败仍保留原文 + 日志（见 [decryptStoredValue]），
     * 一个坏字段不会拖垮整个配置。
     */
    fun load(): Config = loadOrNull() ?: Config()

    /**
     * [load] 的「读不出来就明说」版本：文件缺失或为空属于首次启动，返回
     * [Config] 默认值；文件在但读不出来（损坏 / 被占用 / 整体解密失败）返回
     * `null`，调用方据此放弃写入，不要拿默认值覆盖磁盘上的真实配置。
     */
    fun loadOrNull(): Config? = synchronized(LOCK) {
        val path = configFile()
        if (!fs.exists(path)) return@synchronized Config()
        try {
            val text = fs.read(path) { readUtf8() }
            if (text.isBlank()) return@synchronized Config()
            // 敏感字段的解密发生在序列化器里（EncryptedStringSerializer 等）。
            toml.decodeFromString(Config.serializer(), text)
        } catch (t: Throwable) {
            Log.error(LOG_TAG, "failed to read config: $t")
            null
        }
    }

    /**
     * 原子的读-改-写：**同一把锁内**读 → 交给 [transform] 改 → 落盘，整段不可分割。
     *
     * 凡是「读一份配置、改一个段、再写回」的调用方都必须走这里，而不是
     * [loadOrNull] 改完再 [save]：后者是两段独立临界区，中间的窗口里另一个写入者
     * 插进来会被整段覆盖（详见 [LOCK] 的注释）。
     *
     * [transform] 拿到的是磁盘上的真实配置，返回：
     *  - `success(newConfig)` → 落盘，返回 `success`；
     *  - `failure(...)` → **一个字节都不写**，失败原样交给调用方（校验不过 / 主动放弃）。
     *
     * 配置读不出来时 [transform] 根本不会被调用，直接以 failure 返回 —— 与
     * [loadOrNull] 的语义一致，绝不拿默认值覆盖磁盘上的真实配置。
     */
    fun edit(transform: (Config) -> Result<Config>): Result<Unit> = synchronized(LOCK) {
        val current = loadOrNull()
            ?: return@synchronized Result.failure(
                IllegalStateException("config.toml is unreadable; nothing was written"),
            )
        transform(current).fold(onSuccess = ::save, onFailure = { Result.failure(it) })
    }

    /* -------------------------------- saving ------------------------------- */

    /**
     * Writes [config] to `config.toml` through a temp file + atomic rename,
     * so a crash mid-write can never leave a half-written file behind.
     * 敏感字段的加密发生在序列化器里（编码时进行）。
     *
     * 写入前先把当前 `config.toml` 复制成 `config.toml.bak`：写入是原子的、
     * 不可撤销的，而 [load] 的容错会让一次读失败变成「用默认值整段覆盖」，
     * 留一份上一版是这条错误链上唯一的后悔药。
     */
    fun save(config: Config): Result<Unit> = synchronized(LOCK) {
        runCatching {
            val text = toml.encodeToString(Config.serializer(), config)
            backupCurrentConfig()
            writeFileAtomically(configFile(), text)
        }.onFailure { t ->
            Log.error(LOG_TAG, "failed to write config: $t")
        }
    }

    /**
     * 把当前 `config.toml` 复制成 `config.toml.bak`（只保留一份，每次保存覆盖）。
     * 备份失败不能连累保存本身 —— 它只是保险，缺了它写入依然是正确的。
     */
    private fun backupCurrentConfig() {
        val current = configFile()
        if (!fs.exists(current)) return
        try {
            fs.copy(current, backupFile())
        } catch (t: Throwable) {
            Log.warn(LOG_TAG, "failed to back up config.toml: $t")
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
     * One-time migration for configs that already have a `config.toml`:
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
        } catch (t: IOException) {
            // 收紧权限只是保险：失败不该让 ensureSecretKey 连带失败（那会让整次保存
            // 报「写不了配置」，而实际上文件已经写好了）。
            Log.warn(LOG_TAG, "failed to restrict permissions on $path: $t")
        }
    }
}
