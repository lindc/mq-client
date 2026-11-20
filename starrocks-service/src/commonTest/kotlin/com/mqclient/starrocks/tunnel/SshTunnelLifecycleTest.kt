package com.mqclient.starrocks.tunnel

import com.mqclient.core.ConnectionRegistry
import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import com.mqclient.starrocks.StarRocksConnectionConfig
import com.mqclient.starrocks.StarRocksConnectionManager
import com.mqclient.starrocks.jdbc.FakeJdbcConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 隧道生命周期的守卫：**开了一定要关**。
 *
 * 这条不变式只有在「连库失败」时才容易漏 —— 成功时隧道挂在连接上随连接关闭，
 * 失败时没有任何人持有它。漏关的后果是每次重试都在堡垒机上留一条 SSH 会话，
 * 几次之后就把 max sessions 用满了。
 *
 * 用假隧道（记录 open/close 次数）而不是真 SSH：要断言的是**顺序与配对**，
 * 不是 SSH 协议本身。
 */
class SshTunnelLifecycleTest {

    /** 记录开合次数的假隧道。 */
    private class RecordingTunnel : ConnectionTunnel {
        var closes = 0
            private set
        override val localPort: Int = 12345
        override fun close() { closes++ }
    }

    private class RecordingFactory(
        private val tunnel: RecordingTunnel = RecordingTunnel(),
        private val failOpen: Throwable? = null,
    ) : ConnectionTunnelFactory {
        var opens = 0
            private set
        var lastPortInUrl: Int? = null
        val tunnelRef: RecordingTunnel get() = tunnel

        override suspend fun open(config: StarRocksConnectionConfig): ConnectionTunnel {
            opens++
            failOpen?.let { throw it }
            return tunnel
        }
    }

    private fun context(): ServiceContext {
        val scope = CoroutineScope(SupervisorJob())
        val bus = EventBus(scope)
        return ServiceContext.of("starrocks", bus, scope, JobScheduler(scope, bus), ConnectionRegistry.Empty)
    }

    private fun config(ssh: Boolean = true) = StarRocksConnectionConfig(
        connectionId = "tunnel-test",
        displayName = "tunnel",
        host = "db.internal",
        port = 9030,
        user = "root",
        database = "test_db",
        sshEnabled = ssh,
        sshHost = "jump.example",
        sshUser = "ops",
        sshPassword = "secret",
    )

    @Test
    fun `successful connect keeps the tunnel open until the session closes`() = runBlocking {
        val factory = RecordingFactory()
        val manager = StarRocksConnectionManager(
            context(),
            connector = FakeJdbcConnector(),
            tunnelFactory = factory,
        )

        val connected = manager.connect(config())
        assertTrue(connected.isSuccess, "连接应成功：${connected.exceptionOrNull()}")

        assertEquals(1, factory.opens, "应开一次隧道")
        assertEquals(0, factory.tunnelRef.closes, "连接存活期间隧道不能关")

        manager.close()
        assertEquals(1, factory.tunnelRef.closes, "会话关闭必须连带关隧道")
    }

    @Test
    fun `failed connect still closes the tunnel`() = runBlocking {
        val factory = RecordingFactory()
        val manager = StarRocksConnectionManager(
            context(),
            // 建连阶段就失败 —— 此时隧道已开，但没有任何连接持有它。
            connector = FakeJdbcConnector(FakeJdbcConnector.Behavior.ConnectTimeout),
            tunnelFactory = factory,
        )

        val error = manager.connect(config()).exceptionOrNull()

        assertTrue(error != null, "建连应失败")
        assertEquals(1, factory.opens)
        assertEquals(1, factory.tunnelRef.closes, "连库失败时隧道必须关掉，否则每次重试漏一条 SSH 会话")
    }

    @Test
    fun `tunnel open failure surfaces without touching the jdbc connector`() = runBlocking {
        val factory = RecordingFactory(failOpen = IllegalStateException("jump host unreachable"))
        val connector = FakeJdbcConnector()
        val manager = StarRocksConnectionManager(context(), connector = connector, tunnelFactory = factory)

        val error = manager.connect(config()).exceptionOrNull()

        assertTrue(error != null && error.message?.contains("jump host") == true, "隧道异常应原样上抛，实际 $error")
        assertEquals(0, connector.connectCalls, "隧道都建不起来，不该去连数据库")
    }

    @Test
    fun `ssh disabled never opens a tunnel`() = runBlocking {
        val factory = RecordingFactory()
        val manager = StarRocksConnectionManager(context(), connector = FakeJdbcConnector(), tunnelFactory = factory)

        val connected = manager.connect(config(ssh = false))
        assertTrue(connected.isSuccess, "连接应成功：${connected.exceptionOrNull()}")
        manager.close()

        assertEquals(0, factory.opens, "未启用 SSH 时不应建立隧道")
    }

    @Test
    fun `ssh enabled without an implementation fails loudly`() = runBlocking {
        // 平台不支持隧道时必须报错，而不是静默直连 —— 那会让用户以为"配了隧道却连上了生产库"。
        val manager = StarRocksConnectionManager(context(), connector = FakeJdbcConnector(), tunnelFactory = null)
        val error = manager.connect(config()).exceptionOrNull()
        assertTrue(error is SshUnsupportedException, "应为 SshUnsupportedException，实际 $error")
    }
}
