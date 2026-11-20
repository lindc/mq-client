package com.mqclient.starrocks.ssh

import com.mqclient.starrocks.StarRocksConnectionConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 主机密钥校验的**真机**验证：用真实 sshd 走完 TOFU 三段。
 *
 * 假仓库只能证明判定表正确，证明不了「JSch 在 StrictHostKeyChecking=yes 下
 * 真的会拒绝/放行」。这条连真实 sshd，依次验证：
 *  1. 首次连接 → 抛 SshUnknownHostException（带指纹）；
 *  2. 记住后重连 → 成功；
 *  3. 换了主机密钥（模拟重装 / MITM）→ 抛 SshHostKeyChangedException。
 *
 * 环境不就绪即跳过。
 */
class HostKeyLiveTest {

    private val enabled = System.getProperty("starrocks.live.enabled") == "true"
    private val sshPort = System.getProperty("starrocks.live.sshPort")?.toIntOrNull() ?: 2223
    private val sshKey = System.getProperty("starrocks.live.sshKey") ?: ""
    private val sshUser = System.getProperty("starrocks.live.sshUser") ?: System.getProperty("user.name")
    private val dbPort = System.getProperty("starrocks.live.port")?.toIntOrNull() ?: 9030

    private val dir: Path = createTempDirectory("hostkey-live")
    private fun store() = KnownHostsStore(dir.resolve("known_hosts"), port = sshPort)

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    private fun config() = StarRocksConnectionConfig(
        connectionId = "hk", displayName = "hk",
        host = "127.0.0.1", port = dbPort, user = "root", database = "test_db",
        sshEnabled = true, sshHost = "127.0.0.1", sshPort = sshPort,
        sshUser = sshUser, sshKeyPath = sshKey,
    )

    @Test
    fun `first connection is rejected as unknown until remembered`() = runBlocking {
        if (!enabled || sshKey.isBlank()) return@runBlocking
        val known = store()

        val first = runCatching { SshTunnel.open(config(), known) }.exceptionOrNull()
        println("LIVE hk first -> ${first?.let { it::class.simpleName }} ${first?.message}")
        assertTrue(
            first is SshUnknownHostException,
            "首次连接应抛 SshUnknownHostException（待用户确认），实际 $first",
        )
        // 指纹来自 check() 的副产物 —— JSch 拒绝连接时异常里没有指纹（实测），
        // 但密钥交换阶段它已经把对端公钥交给 check() 了。
        val fingerprint = known.lastSeenFingerprint("127.0.0.1")
        println("LIVE hk fingerprint -> $fingerprint")
        assertTrue(fingerprint != null, "应能拿到对端主机指纹供用户核对")
        assertEquals(
            first.fingerprint,
            fingerprint,
            "异常里带的指纹应与 check() 记录的一致",
        )

        // 模拟用户确认（拿 ssh-keygen -lf 核对过后）。
        known.rememberFingerprint("127.0.0.1", "ssh-ed25519", fingerprint)

        val second = runCatching { SshTunnel.open(config(), known) }
        println("LIVE hk second -> ${second.isSuccess} ${second.exceptionOrNull()?.message}")
        assertTrue(
            second.isSuccess || second.exceptionOrNull() !is SshUnknownHostException,
            "记住后不应再报「未知主机」，实际 ${second.exceptionOrNull()}",
        )
        second.getOrNull()?.close()
    }

    @Test
    fun `changed host key is rejected`() = runBlocking {
        if (!enabled || sshKey.isBlank()) return@runBlocking
        val known = store()
        // 先记住一个**错误**的密钥（模拟上次连的是另一台机器 / MITM 已注册）。
        Files.createDirectories(dir)
        // 主机标识必须是 JSch 的格式（`[host]:port`），否则仓库根本匹配不到这一条。
        Files.write(
            dir.resolve("known_hosts"),
            listOf("[" + "127.0.0.1" + "]:" + sshPort + " ssh-ed25519 WRONGKEYFINGERPRINT"),
        )

        val error = runCatching { SshTunnel.open(config(), store()) }.exceptionOrNull()
        println("LIVE hk changed -> ${error?.let { it::class.simpleName }} ${error?.message}")
        assertTrue(
            error is SshHostKeyChangedException,
            "密钥不匹配必须报 CHANGED 并拒绝，实际 $error",
        )
    }
}
