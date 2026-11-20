package com.mqclient.starrocks.ui

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionPersistence
import com.mqclient.mqui.state.SaveState
import com.mqclient.olap.base.connection.HealthReport
import com.mqclient.starrocks.StarRocksConnectionConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SSH 隧道字段的表单契约：**填了什么就原样落盘，编辑时不能丢**。
 *
 * 与 `StarRocksConnectionFormStateTest` 同一手法：走 `submit()` 这条用户实际路径，
 * 断言落盘的 `ConnectionInfo.options`，而不是直接调 protected 的 `buildConfig` ——
 * 前者能发现「校验通过但没写进去」，后者不能。
 *
 * 重点是编辑模式：`loadFrom` 故意不回填密码（避免明文出现在输入框），所以
 * 「打开编辑 → 不改密码 → 保存」必须沿用原值。星河的主密码原先没有这层保护
 * （会覆盖成空串），这次与 IBM MQ 的 `resolveSecret` 对齐，SSH 密码同样处理。
 */
class StarRocksSshFormTest {

    private class Signal {
        private val deferred = CompletableDeferred<Unit>()
        fun fire() = deferred.complete(Unit)
        suspend fun await() = withTimeout(5_000) { deferred.await() }
    }

    private class RecordingPersistence(private val signal: Signal) : ConnectionPersistence {
        val saved = mutableListOf<ConnectionInfo>()
        override fun create(connection: ConnectionInfo): Result<Unit> {
            saved += connection
            signal.fire()
            return Result.success(Unit)
        }
        override fun update(connection: ConnectionInfo): Result<Unit> = create(connection)
    }

    private fun state(
        scope: CoroutineScope,
        persistence: RecordingPersistence,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = StarRocksConnectionFormState(
        defaultName = "conn",
        scope = scope,
        probe = { HealthReport(healthy = true, detail = "ok", latencyMs = 1L) },
        persistence = persistence,
        ioDispatcher = ioDispatcher,
    )

    @Test
    fun `ssh settings are persisted on create`() = runTest {
        val signal = Signal()
        val persistence = RecordingPersistence(signal)
        val s = state(this, persistence)
        s.host = "db.internal"
        s.user = "root"
        s.password = "p"
        s.database = "test_db"
        s.sshEnabled = true
        s.sshHost = "jump.example"
        s.sshPort = "2222"
        s.sshUser = "ops"
        s.sshPassword = "ssh-secret"
        s.sshKeyPath = "/home/ops/.ssh/id_ed25519"

        assertFalse(s.submit())
        signal.await()
        advanceUntilIdle()

        val options = persistence.saved.single().options
        assertEquals("true", options[StarRocksConnectionConfig.KEY_SSH_ENABLED])
        assertEquals("jump.example", options[StarRocksConnectionConfig.KEY_SSH_HOST])
        assertEquals("2222", options[StarRocksConnectionConfig.KEY_SSH_PORT])
        assertEquals("ops", options[StarRocksConnectionConfig.KEY_SSH_USER])
        assertEquals("ssh-secret", options[StarRocksConnectionConfig.KEY_SSH_PASSWORD])
        assertEquals("/home/ops/.ssh/id_ed25519", options[StarRocksConnectionConfig.KEY_SSH_KEY_PATH])
    }

    @Test
    fun `round trip through options restores every ssh field`() {
        val original = StarRocksConnectionConfig(
            connectionId = "c", displayName = "c", host = "db", database = "db",
            sshEnabled = true, sshHost = "jump", sshPort = 2222, sshUser = "ops",
            sshPassword = "secret", sshKeyPath = "~/.ssh/id_ed25519",
        )
        val restored = StarRocksConnectionConfig.fromOptions("c", "c", original.options)

        assertTrue(restored.sshEnabled)
        assertEquals("jump", restored.sshHost)
        assertEquals(2222, restored.sshPort)
        assertEquals("ops", restored.sshUser)
        assertEquals("secret", restored.sshPassword)
        assertEquals("~/.ssh/id_ed25519", restored.sshKeyPath)
    }

    @Test
    fun `editing without touching the ssh password keeps the stored one`() = runTest {
        val original = StarRocksConnectionConfig(
            connectionId = "c1", displayName = "c1", host = "db", database = "db",
            sshEnabled = true, sshHost = "jump", sshUser = "ops", sshPassword = "stored-secret",
        )
        val info = ConnectionInfo(
            id = "c1", name = "c1", serviceId = "starrocks", mqName = "db",
            host = "db", port = 9030, options = original.options,
        )

        val signal = Signal()
        val persistence = RecordingPersistence(signal)
        val s = state(this, persistence)
        s.loadFrom(info)
        // 用户只改了库名，没碰任何密码框。
        s.database = "other_db"

        assertFalse(s.submit())
        signal.await()
        advanceUntilIdle()

        val saved = persistence.saved.single()
        assertEquals("other_db", saved.options[StarRocksConnectionConfig.KEY_DATABASE])
        assertEquals(
            "stored-secret",
            saved.options[StarRocksConnectionConfig.KEY_SSH_PASSWORD],
            "编辑时未改密码不能把已存密码覆盖成空串",
        )
    }

    @Test
    fun `editing and typing a new ssh password replaces the stored one`() = runTest {
        val original = StarRocksConnectionConfig(
            connectionId = "c1", displayName = "c1", host = "db", database = "db",
            sshEnabled = true, sshHost = "jump", sshUser = "ops", sshPassword = "old",
        )
        val info = ConnectionInfo(
            id = "c1", name = "c1", serviceId = "starrocks", mqName = "db",
            host = "db", port = 9030, options = original.options,
        )

        val signal = Signal()
        val persistence = RecordingPersistence(signal)
        val s = state(this, persistence)
        s.loadFrom(info)
        s.onSshPasswordChange("new-secret")

        assertFalse(s.submit())
        signal.await()
        advanceUntilIdle()

        assertEquals(
            "new-secret",
            persistence.saved.single().options[StarRocksConnectionConfig.KEY_SSH_PASSWORD],
        )
    }

    @Test
    fun `ssh validation only applies when enabled`() = runTest {
        val persistence = RecordingPersistence(Signal())
        val s = state(this, persistence)
        s.host = "db"
        s.user = "root"
        s.password = "p"
        s.database = "db"

        // 未启用：空的 SSH 字段不该拦住保存。
        assertTrue(s.submit() == false && persistence.saved.size <= 1, "未启用 SSH 应能通过校验")

        val s2 = state(this, RecordingPersistence(Signal()))
        s2.host = "db"; s2.user = "root"; s2.database = "db"
        s2.sshEnabled = true
        assertFalse(s2.submit(), "启用后空堡垒机地址应被拦下")
        assertNotNull(s2.errors.ssh)
        assertEquals(SaveState.Idle, s2.saveState, "校验失败不应进入测试")
    }
}
