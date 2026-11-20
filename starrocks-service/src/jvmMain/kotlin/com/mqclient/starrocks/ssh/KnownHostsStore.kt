package com.mqclient.starrocks.ssh

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import com.mqclient.core.log.LogLevel
import com.mqclient.starrocks.log.StarRocksLog
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 只读的「已知主机密钥」仓库（相当于 SSH 的 `known_hosts`）。
 *
 * ## 为什么需要它
 *
 * 此前隧道用 `StrictHostKeyChecking=no`：首连不校验主机密钥，等于**任何能拦到
 * 流量的人都能冒充堡垒机**。桌面应用没有终端可交互确实是限制，但正确做法不是
 * 放弃校验，而是「首连记住、之后比对」—— 这也是 OpenSSH 的默认行为。
 *
 * ## 信任模型
 *
 * - 未知主机 → [check] 返回 [HostKeyRepository.NOT_INCLUDED]，连接阶段由
 *   [SshTunnel] 把指纹展示给用户确认，确认后写入本仓库（TOFU，trust on first use）；
 * - 已知且一致 → [OK]；
 * - 已知但**不一致** → [CHANGED]，连接失败并提示"主机密钥已改变"。
 *   这是最重要的一条：MITM 与"服务器重装"在客户端看来都是这个信号，必须
 *   让用户明确决定，不能静默接受。
 *
 * ## 存储
 *
 * `<configDir>/known_hosts`，格式与 OpenSSH 兼容（`host keytype base64`），
 * 这样用户也能用 `ssh-keygen -F host` 核对。写入走「临时文件 + 原子替换」，
 * 避免写到一半断电把整个文件毁掉（与 CSV 导出同一模式）。
 *
 * @param file 存储路径（测试可指向临时目录）
 */
internal class KnownHostsStore(
    private val file: Path = defaultPath(),
    /**
     * 这台主机的 SSH 端口。
     *
     * JSch 在 `check(host, key)` 里传的 host 是 **`[host]:port`**（OpenSSH
     * known_hosts 的标准格式，实测 `[127.0.0.1]:2223`），而不是裸主机名。
     * 存储与查询统一用这个格式，否则「记住的主机永远匹配不上」—— 表现就是
     * 每次连接都当作未知主机，CHANGED 也永远报不出来。
     */
    private val port: Int = 22,
) : HostKeyRepository {

    /** 归一化主机标识：`host:port`（非 22 端口带方括号），与 JSch / OpenSSH 一致。 */
    fun hostId(host: String): String = if (port == 22) host else "[$host]:$port"

    /** 解析后的条目：host → (keyType → 指纹的 SHA-256 base64)。 */
    private data class Entry(val host: String, val keyType: String, val fingerprint: String)

    private val entries: MutableList<Entry> = load()

    /**
     * 最近一次 [check] 看到的主机密钥（host → 指纹 / 类型）。
     *
     * **这是拿指纹的正确位置**（实测）：JSch 在密钥交换阶段把对端公钥交给
     * `check(host, key)`，用它可以算出与 `ssh-keygen -lf` 一致的指纹；而
     * `add()` 在 `StrictHostKeyChecking=ask` 下**根本不会被调用**，异常消息也
     * 只有一句 `reject HostKey: [host]:port`（strict=yes 时）。
     *
     * 所以首连确认的指纹从这份记录里取，不另做一次探测连接。
     */
    private var lastSeenHost: String? = null
    private var lastSeenFingerprint: String? = null
    private var lastSeenType: String? = null

    override fun check(host: String, key: ByteArray): Int {
        // 先记下这一对（无论结论如何，UI 都可能需要展示指纹）。
        // host 可能已经是 `[host]:port` 形式（JSch 传入），也可能是裸主机名
        // （调用方查询）—— 统一归一化后再比较。
        val id = if (host.startsWith("[")) host else hostId(host)
        lastSeenHost = id
        lastSeenFingerprint = fingerprintOf(key)
        lastSeenType = null
        val known = entries.filter { it.host == id }
        if (known.isEmpty()) return HostKeyRepository.NOT_INCLUDED
        val candidate = fingerprintOf(key)
        return if (known.any { it.fingerprint == candidate }) {
            HostKeyRepository.OK
        } else {
            HostKeyRepository.CHANGED
        }
    }

    /**
     * 记住一台主机的密钥（首次连接确认后调用）。
     *
     * 同一 host 的旧条目会被**替换**而不是追加：密钥变更后旧条目已无意义，
     * 留着会让"CHANGED"永远不消失，用户每连一次都要确认一遍。
     */
    fun remember(host: String, keyType: String, key: ByteArray) {
        val id = if (host.startsWith("[")) host else hostId(host)
        entries.removeAll { it.host == id }
        entries += Entry(id, keyType, fingerprintOf(key))
        persist()
        StarRocksLog.conn("ssh known_hosts: remember host=$host type=$keyType ($entries.size entries)")
    }

    /**
     * 直接以**指纹**记住主机（已经算好了的情况）。
     *
     * 与 [remember] 的区别：那个收原始密钥字节再算指纹；这里收指纹本身。
     * 用于首连确认流程 —— UI 拿到的是 [probeHostKey] 算好的指纹，没必要再把
     * base64 密钥解码一遍。
     */
    fun rememberFingerprint(host: String, keyType: String, fingerprint: String) {
        val id = if (host.startsWith("[")) host else hostId(host)
        entries.removeAll { it.host == id }
        entries += Entry(id, keyType, fingerprint)
        persist()
    }

    /** 最近一次 [check] 看到的主机指纹（首连确认用）。 */
    fun lastSeenFingerprint(host: String): String? {
        val id = if (host.startsWith("[")) host else hostId(host)
        return lastSeenFingerprint?.takeIf { lastSeenHost == id }
    }

    /** 已记住的主机数（测试 / 诊断用）。 */
    fun size(): Int = entries.size

    /** 该主机是否已记住（未知主机要弹确认）。 */
    fun isKnown(host: String): Boolean {
        val id = if (host.startsWith("[")) host else hostId(host)
        return entries.any { it.host == id }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) {
        // 由 remember() 负责写入（这里需要先经用户确认，不能由 JSch 静默调用）。
        // HostKey.key 是 base64 字符串；存指纹而非原文，避免格式差异导致比较失败。
        hostkey ?: return
        entries.removeAll { it.host == hostkey.host }
        entries += Entry(hostkey.host, hostkey.type, hostkey.key)
        persist()
    }

    override fun remove(host: String?, type: String?) {
        if (host == null) return
        entries.removeAll { it.host == host && (type == null || it.keyType == type) }
        persist()
    }

    override fun remove(host: String?, type: String?, key: ByteArray?) = remove(host, type)

    override fun getKnownHostsRepositoryID(): String = file.toString()

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    /* ------------------------------ 存储 ------------------------------ */

    private fun load(): MutableList<Entry> {
        if (!Files.exists(file)) return mutableListOf()
        val parsed = runCatching {
            Files.readAllLines(file)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split(' ')
                    if (parts.size >= 3) Entry(parts[0], parts[1], parts[2]) else null
                }
        }
        return parsed.getOrElse {
            // 读坏了就当作空表：首次连接会重新询问用户确认，不会被坏文件卡死。
            // 注意不能用 getOrDefault —— onFailure 已经消费了异常，getOrDefault 永远
            // 拿不到它，损坏文件会被静默当成空表且不留日志（实测踩过）。
            StarRocksLog.conn(LogLevel.WARN, "ssh known_hosts: read failed, starting empty (${it.message})")
            emptyList()
        }.toMutableList()
    }

    private fun persist() {
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.write(
                tmp,
                entries.map { "${it.host} ${it.keyType} ${it.fingerprint}" },
            )
            // 与 CSV 导出同一模式：先写临时文件再替换，不会留下半截 known_hosts。
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.onFailure {
            StarRocksLog.conn(LogLevel.WARN, "ssh known_hosts: persist failed (${it.message})")
        }
    }

    companion object {
        /** 默认路径：`<configDir>/known_hosts`（设置里的配置目录换了会跟着走）。 */
        fun defaultPath(): Path {
            // ConfigManager.configDir() 返回 okio.Path，本模块没有引入 okio 依赖；
            // 按它的同一口径（同一个 system property）拼路径即可，不新增依赖。
            val override = System.getProperty("mqclient.config.dir")
            val root = override ?: (System.getProperty("user.home") + File.separator + ".mqclient")
            return File(root).toPath().resolve("known_hosts")
        }

        /**
         * 密钥指纹：密钥本身（DER / SSH wire 格式）的 **SHA-256**，base64 编码。
         *
         * 与 `ssh-keygen -lf` 的 SHA256 指纹同源 —— 用户可以用系统的 ssh 命令
         * 交叉核对，不必信任本应用的自述。
         */
        fun fingerprintOf(key: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            return java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}

/**
 * 未知主机需要用户确认（TOFU）。
 *
 * 携带指纹，供 UI 原样展示 —— 用户要能拿它跟运维给的指纹对比，才谈得上"确认"。
 */
class SshUnknownHostException(
    val host: String,
    val keyType: String,
    val fingerprint: String,
) : Exception("Unknown SSH host key for $host ($keyType, SHA256:$fingerprint)")

/**
 * 主机密钥与已记录的不一致 —— **必须让用户决定**，不能自动接受。
 *
 * 与 [SshUnknownHostException] 分开：前者是"第一次见"，后者是"和上次不一样"，。
 * 后者更危险（可能是中间人，也可能是服务器重装），提示语与处理动作都不同。
 */
class SshHostKeyChangedException(
    val host: String,
    val keyType: String,
    val fingerprint: String,
) : Exception("SSH host key for $host changed ($keyType, SHA256:$fingerprint)")

/**
 * UI 只需要「记住一台主机的指纹」这一件事。
 *
 * 单独暴露而不是让 UI 直接 new [KnownHostsStore]：那个类继承了 JSch 的
 * `HostKeyRepository`，暴露出去等于把 JSch 拖进 UI 模块的依赖面 —— 而 UI
 * 完全不需要知道 SSH 实现细节。
 */
object StarRocksKnownHosts {

    /** 记住主机的指纹（用户核对确认后调用）。 */
    fun remember(host: String, port: Int, keyType: String, fingerprint: String) {
        KnownHostsStore(port = port).rememberFingerprint(host, keyType, fingerprint)
    }

    /** 该主机是否已记住（诊断 / 测试用）。 */
    fun isKnown(host: String, port: Int): Boolean = KnownHostsStore(port = port).isKnown(host)
}
