package com.mqclient.starrocks.ssh

import com.mqclient.core.ConnectionRegistry
import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import com.mqclient.starrocks.StarRocksConnectionConfig
import com.mqclient.starrocks.StarRocksConnectionManager
import com.mqclient.starrocks.jdbc.MysqlJdbcConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SSH 隧道的**真机**验证：经真实 SSH 会话转发到本地 StarRocks。
 *
 * 用假隧道只能证明顺序，证明不了「隧道真的能把流量转过去」—— 这条走真实
 * JSch 认证、端口转发、URL 改写与 JDBC 连通。环境不就绪即跳过（与其它
 * `*LiveTest` 同约定）。
 */
class SshTunnelLiveTest {

    private val enabled = System.getProperty("starrocks.live.enabled") == "true"
    private val dbHost = System.getProperty("starrocks.live.host") ?: "127.0.0.1"
    private val dbPort = System.getProperty("starrocks.live.port")?.toIntOrNull() ?: 9030
    private val dbPassword = System.getProperty("starrocks.live.password") ?: ""
    private val sshPort = System.getProperty("starrocks.live.sshPort")?.toIntOrNull() ?: 2222
    private val sshKey = System.getProperty("starrocks.live.sshKey") ?: ""
    private val sshUser = System.getProperty("starrocks.live.sshUser") ?: System.getProperty("user.name")

    private fun context(): ServiceContext {
        val scope = CoroutineScope(SupervisorJob())
        val bus = EventBus(scope)
        return ServiceContext.of("starrocks", bus, scope, JobScheduler(scope, bus), ConnectionRegistry.Empty)
    }

    private fun config() = StarRocksConnectionConfig(
        connectionId = "ssh-live",
        displayName = "ssh-live",
        host = dbHost,
        port = dbPort,
        user = "root",
        password = dbPassword,
        database = "test_db",
        sshEnabled = true,
        sshHost = "127.0.0.1",
        sshPort = sshPort,
        sshUser = sshUser,
        sshKeyPath = sshKey,
    )

    @Test
    fun `query runs through a real ssh tunnel`() = runBlocking {
        if (!enabled || sshKey.isBlank()) return@runBlocking

        val manager = StarRocksConnectionManager(
            context(),
            connector = MysqlJdbcConnector(),
            tunnelFactory = { cfg -> SshTunnel.open(cfg) },
        )

        val connected = manager.connect(config())
        println("LIVE ssh connect -> ${connected.isSuccess} ${connected.exceptionOrNull()}")
        assertTrue(connected.isSuccess, "经隧道应能连上 StarRocks：${connected.exceptionOrNull()}")

        var rows = 0
        manager.executeQuery("SELECT count(*) FROM test_db.orders", "ssh-live").collect { batch ->
            rows += batch.rows.size
        }
        println("LIVE ssh query rows=$rows")
        assertTrue(rows > 0, "经隧道应能查到数据")

        manager.close()
        println("LIVE ssh closed")
    }
}
