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
import kotlin.test.assertTrue

/**
 * 连接表单状态的离线单测（**MQ 同款保存链路**）：
 * 「完成」→ 测试连接 → 成功落盘 + onSaved / 失败 SaveState.Failed + confirmSave。
 * 探针与落盘都是注入的 fake，不依赖真实数据库 / 不写 config.toml。
 */
class StarRocksConnectionFormStateTest {

    /** 一次性完成信号：fake 在工作完成时 fire，测试体 await 后再断言。 */
    private class Signal {
        private val deferred = CompletableDeferred<Unit>()
        fun fire() {
            deferred.complete(Unit)
        }

        suspend fun await() = withTimeout(5_000) { deferred.await() }
    }

    /** 记录落盘的 ConnectionInfo 替身。 */
    private class RecordingPersistence(private val signal: Signal) : ConnectionPersistence {
        val created = mutableListOf<ConnectionInfo>()
        var failMessage: String? = null
        override fun create(connection: ConnectionInfo): Result<Unit> {
            created += connection
            signal.fire()
            return failMessage?.let { Result.failure(IllegalStateException(it)) }
                ?: Result.success(Unit)
        }

        override fun update(connection: ConnectionInfo): Result<Unit> = create(connection)
    }

    private fun state(
        scope: CoroutineScope = CoroutineScope(SupervisorJob()),
        probe: suspend (StarRocksConnectionConfig) -> HealthReport = {
            HealthReport(healthy = true, detail = "SELECT 1 ok", latencyMs = 12L)
        },
        persistence: RecordingPersistence,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ): StarRocksConnectionFormState = StarRocksConnectionFormState(
        defaultName = "连接 1",
        scope = scope,
        probe = probe,
        persistence = persistence,
        ioDispatcher = ioDispatcher,
    )

    // ---------------- 草稿 / placeholder ----------------

    @Test
    fun `connection name starts empty so placeholder is shown`() {
        val s = state(persistence = RecordingPersistence(Signal()))
        assertEquals("", s.name)
        assertEquals("连接 1", s.defaultName)
    }

    @Test
    fun `port input keeps digits only`() {
        val s = state(persistence = RecordingPersistence(Signal()))
        s.onPortChange("90a30")
        assertEquals("9030", s.port)
    }

    // ---------------- 校验 ----------------

    @Test
    fun `blank host and bad port block submit with field errors`() = runTest {
        val persistence = RecordingPersistence(Signal())
        val s = state(this, persistence = persistence)
        s.host = ""
        s.port = "99999"

        assertFalse(s.submit())
        assertTrue(s.errors.host != null)
        assertTrue(s.errors.port != null)
        assertTrue(persistence.created.isEmpty(), "校验失败不应落盘")
        assertEquals(SaveState.Idle, s.saveState)
    }

    // ---------------- 完成 → 测试成功 → 落盘 ----------------

    @Test
    fun `submit tests connection then persists and fires onSaved`() = runTest {
        val signal = Signal()
        val persistence = RecordingPersistence(signal)
        val s = state(this, persistence = persistence)
        var closed = false
        s.onSaved = { closed = true }
        s.host = "sr.example.com"
        s.port = "9031"
        s.user = "reader"
        s.password = "s3cret"
        s.database = "demo_db"

        assertFalse(s.submit(), "MQ 同款：submit 恒返回 false，宿主保持对话框打开")
        signal.await()
        advanceUntilIdle()
        assertEquals(1, persistence.created.size)
        val info = persistence.created.single()
        assertEquals("starrocks", info.serviceId)
        assertEquals("连接 1", info.name, "名称留空时由宿主默认名兜底")
        assertEquals("sr.example.com", info.host)
        assertEquals(9031, info.port)
        assertEquals("demo_db", info.mqName)
        assertEquals(
            mapOf(
                StarRocksConnectionConfig.KEY_HOST to "sr.example.com",
                StarRocksConnectionConfig.KEY_PORT to "9031",
                StarRocksConnectionConfig.KEY_USER to "reader",
                StarRocksConnectionConfig.KEY_PASSWORD to "s3cret",
                StarRocksConnectionConfig.KEY_DATABASE to "demo_db",
            ),
            info.options,
        )
        assertTrue(closed, "落盘成功后应回调宿主关窗")
        assertEquals(SaveState.Idle, s.saveState)
    }

    // ---------------- 完成 → 测试失败 → 确认框 ----------------

    @Test
    fun `failed test shows Failed state with friendly detail and no persistence`() = runTest {
        val signal = Signal()
        val persistence = RecordingPersistence(signal)
        val s = state(this, probe = {
            signal.fire()
            HealthReport(false, "Permission denied [SQLState=28000, ErrorCode=1045]", 5L)
        }, persistence = persistence)

        assertFalse(s.submit())
        signal.await()
        advanceUntilIdle()
        val failed = s.saveState as SaveState.Failed
        assertTrue(failed.message.contains("ErrorCode=1045"), "确认框要能看出协议错误码")
        assertTrue(persistence.created.isEmpty(), "测试失败不应自动落盘")
    }

    @Test
    fun `confirmSave persists the tested snapshot after a failed test`() = runTest {
        val testSignal = Signal()
        val persistence = RecordingPersistence(Signal())
        val s = state(this, probe = {
            testSignal.fire()
            HealthReport(false, "connection refused", 1L)
        }, persistence = persistence)
        s.submit()
        testSignal.await()
        advanceUntilIdle()
        assertTrue(s.saveState is SaveState.Failed)

        s.confirmSave()
        advanceUntilIdle()
        assertEquals(1, persistence.created.size, "「仍要保存」按测试时的配置快照落盘")
        assertEquals(SaveState.Idle, s.saveState)
    }

    @Test
    fun `dismissFailed closes dialog and keeps the form editable`() = runTest {
        val signal = Signal()
        val s = state(this, probe = {
            signal.fire()
            HealthReport(false, "connection refused", 1L)
        }, persistence = RecordingPersistence(Signal()))
        s.submit()
        signal.await()
        advanceUntilIdle()
        s.dismissFailed()
        assertEquals(SaveState.Idle, s.saveState)
        assertEquals("localhost", s.host)
    }

    @Test
    fun `save failure is surfaced in the panel`() = runTest {
        val signal = Signal()
        val persistence = RecordingPersistence(signal).apply { failMessage = "disk full" }
        val s = state(this, persistence = persistence)
        s.submit()
        signal.await()
        advanceUntilIdle()
        assertEquals("disk full", s.saveError)
    }

    // ---------------- reset / loadFrom ----------------

    @Test
    fun `reset clears draft errors and save flow`() {
        val s = state(persistence = RecordingPersistence(Signal()))
        s.host = "changed"
        s.submit()
        s.reset("连接 2")
        assertEquals("", s.name)
        assertEquals("连接 2", s.defaultName)
        assertEquals(StarRocksConnectionConfig.DEFAULT_HOST, s.host)
        assertEquals(StarRocksConnectionConfig.DEFAULT_PORT.toString(), s.port)
        assertFalse(s.errors.hasAny)
        assertEquals(SaveState.Idle, s.saveState)
    }

    @Test
    fun `loadFrom prefills from persisted options and keeps password blank`() {
        val s = state(persistence = RecordingPersistence(Signal()))
        s.loadFrom(
            ConnectionInfo(
                id = "conn-1",
                name = "prod",
                serviceId = "starrocks",
                mqName = "warehouse",
                host = "sr.prod",
                port = 9030,
                options = mapOf(
                    StarRocksConnectionConfig.KEY_HOST to "sr.prod",
                    StarRocksConnectionConfig.KEY_PORT to "9030",
                    StarRocksConnectionConfig.KEY_USER to "analyst",
                    StarRocksConnectionConfig.KEY_PASSWORD to "encrypted-at-rest",
                    StarRocksConnectionConfig.KEY_DATABASE to "warehouse",
                ),
            ),
        )
        assertEquals("prod", s.name)
        assertEquals("sr.prod", s.host)
        assertEquals("analyst", s.user)
        assertEquals("warehouse", s.database)
        assertEquals("", s.password, "编辑模式密码留空，用户不动就沿用原值")
        assertEquals("conn-1", s.editingId)
    }
}
