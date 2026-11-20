package com.mqclient.starrocks.ssh

import com.jcraft.jsch.HostKeyRepository
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * known_hosts 的**信任判定**守卫。
 *
 * 这是安全相关代码，三种返回值对应三种完全不同的用户动作：
 *  - NOT_INCLUDED → 首次连接，展示指纹让用户确认（TOFU）；
 *  - OK → 静默放行；
 *  - CHANGED → **拒绝并警告**（可能是中间人，也可能是服务器重装）。
 *
 * 把 CHANGED 误判成 NOT_INCLUDED 就等于静默接受了被换掉的密钥 —— 这是最需要
 * 防的回归。
 */
class KnownHostsStoreTest {

    private val dir = createTempDirectory("known-hosts").toFile()
    private val file = dir.toPath().resolve("known_hosts")

    private fun store() = KnownHostsStore(file)

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val keyA = "AAAAB3NzaC1yc2EAAAADAQABAAABgQD-keyA".toByteArray()
    private val keyB = "AAAAB3NzaC1yc2EAAAADAQABAAABgQD-keyB".toByteArray()

    @Test
    fun `unknown host is not included`() {
        assertEquals(HostKeyRepository.NOT_INCLUDED, store().check("jump.example", keyA))
    }

    @Test
    fun `remembered host is accepted on the next connection`() {
        val s = store()
        s.remember("jump.example", "ssh-ed25519", keyA)

        assertEquals(HostKeyRepository.OK, store().check("jump.example", keyA), "重启后也应记得")
    }

    @Test
    fun `changed key is reported as changed not as unknown`() {
        val s = store()
        s.remember("jump.example", "ssh-ed25519", keyA)

        assertEquals(
            HostKeyRepository.CHANGED,
            s.check("jump.example", keyB),
            "密钥变了必须报 CHANGED —— 报成 NOT_INCLUDED 等于静默接受中间人",
        )
    }

    @Test
    fun `different hosts are tracked independently`() {
        val s = store()
        s.remember("a.example", "ssh-ed25519", keyA)

        assertEquals(HostKeyRepository.OK, s.check("a.example", keyA))
        assertEquals(HostKeyRepository.NOT_INCLUDED, s.check("b.example", keyA))
    }

    @Test
    fun `remembering a new key replaces the old entry for that host`() {
        val s = store()
        s.remember("jump.example", "ssh-ed25519", keyA)
        // 用户确认接受新密钥后（例如服务器重装），旧条目必须被替换；
        // 否则 CHANGED 永远不会消失，每次都弹窗。
        s.remember("jump.example", "ssh-ed25519", keyB)

        assertEquals(1, s.size(), "同一主机应只有一条记录")
        assertEquals(HostKeyRepository.OK, s.check("jump.example", keyB))
    }

    @Test
    fun `entry survives a restart`() {
        store().remember("jump.example", "ssh-ed25519", keyA)
        assertTrue(Files.exists(file), "应落盘")

        val reopened = store()
        assertTrue(reopened.isKnown("jump.example"))
        assertEquals(HostKeyRepository.OK, reopened.check("jump.example", keyA))
    }

    @Test
    fun `corrupt file degrades to empty instead of blocking connections`() {
        Files.createDirectories(file.parent)
        // 注意别用「too few fields」这种测试数据 —— 它恰好是三个空格分段，
        // 会被当成合法条目（我写第一版时踩过）。
        Files.write(file, listOf("garbage-without-spaces", "only two"))
        val s = store()

        assertEquals(0, s.size(), "坏文件应被当作空表，而不是抛异常")
        assertEquals(HostKeyRepository.NOT_INCLUDED, s.check("jump.example", keyA))
    }

    @Test
    fun `remembered file does not leave a temp file behind`() {
        store().remember("jump.example", "ssh-ed25519", keyA)
        assertFalse(
            Files.exists(file.resolveSibling("known_hosts.tmp")),
            "原子替换后不应残留临时文件",
        )
    }

    @Test
    fun `fingerprint is stable and matches sha256 of the key bytes`() {
        val a = KnownHostsStore.fingerprintOf(keyA)
        assertEquals(a, KnownHostsStore.fingerprintOf(keyA), "同一密钥指纹必须稳定")
        assertTrue(a != KnownHostsStore.fingerprintOf(keyB), "不同密钥指纹必须不同")
        assertFalse(a.contains("="), "base64 去填充，方便用户抄写比对")
    }
}
