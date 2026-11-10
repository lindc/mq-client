package com.mqclient.starrocks

import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import com.mqclient.olap.base.error.OlapErrorCode
import com.mqclient.starrocks.jdbc.FakeJdbcConnector
import com.mqclient.starrocks.jdbc.JdbcFailure
import com.mqclient.starrocks.jdbc.StarRocksErrorDetail
import com.mqclient.starrocks.jdbc.StarRocksSqlDialect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S1 白天的自证单测：`testConnection` / `healthCheck` 的成功、超时、SQL 异常
 * 三条路径 + `finally` 关闭语义。全部用 [FakeJdbcConnector]，不依赖真实数据库。
 */
class StarRocksConnectionManagerTest {

    /** 基类需要一个 ServiceContext（EventBus / JobScheduler 都是可离线构造的）。 */
    private fun testContext(): ServiceContext {
        val scope = CoroutineScope(SupervisorJob())
        val bus = EventBus(scope)
        return ServiceContext.of("starrocks", bus, scope, JobScheduler(scope, bus))
    }

    private fun config(): StarRocksConnectionConfig = StarRocksConnectionConfig(
        connectionId = "test-conn",
        displayName = "test",
        options = mapOf(
            StarRocksConnectionConfig.KEY_HOST to "localhost",
            StarRocksConnectionConfig.KEY_PORT to "9030",
            StarRocksConnectionConfig.KEY_USER to "root",
        ),
    )

    private fun manager(connector: FakeJdbcConnector): StarRocksConnectionManager =
        StarRocksConnectionManager(testContext(), connector = connector)

    // ---------------- testConnection ----------------

    @Test
    fun `success returns healthy and closes the connection`() = runTest {
        val connector = FakeJdbcConnector(FakeJdbcConnector.Behavior.Success)
        val report = manager(connector).testConnection(config())

        assertTrue(report.healthy, "SELECT 1 成功时应 healthy=true")
        assertTrue(report.detail.contains("SELECT 1"), "成功文案应带探针语句")
        assertTrue(report.latencyMs >= 0)
        assertEquals(1, connector.connectCalls)
        assertEquals(1, connector.probeCalls)
        assertEquals(1, connector.connectionCloseCalls, "连接必须在 finally 中关闭")
        assertEquals(0, connector.openConnections, "不允许泄漏未关闭连接")
    }

    @Test
    fun `connect timeout returns unhealthy with TIMEOUT mapping and no leaked connection`() = runTest {
        val connector = FakeJdbcConnector(FakeJdbcConnector.Behavior.ConnectTimeout)
        val report = manager(connector).testConnection(config())

        assertFalse(report.healthy)
        assertTrue(report.detail.contains(OlapErrorCode.TIMEOUT.errorKey), "应包含超时错误键")
        assertTrue(report.detail.contains("connect timeout"))
        assertEquals(0, connector.openConnections, "建连失败时没有可泄漏的连接")
        assertEquals(0, connector.connectionCloseCalls, "未建立的连接不应被 close")
    }

    @Test
    fun `SQL exception is mapped with SQLState ErrorCode and friendly text`() = runTest {
        val connector = FakeJdbcConnector(
            FakeJdbcConnector.Behavior.SqlFailure(
                sqlState = "28000",
                errorCode = 1045,
                message = "Access denied for user 'root'",
            ),
        )
        val report = manager(connector).testConnection(config())

        assertFalse(report.healthy)
        assertTrue(report.detail.contains("ErrorCode=1045"), "必须保留协议错误码")
        assertTrue(report.detail.contains("SQLState=28000"))
        assertTrue(report.detail.contains(OlapErrorCode.PERMISSION_DENIED.errorKey))
        assertTrue(report.detail.contains("Permission denied"), "应翻译成友好文案")
        assertEquals(0, connector.openConnections, "connect 阶段失败不应泄漏连接")
    }

    @Test
    fun `probe-stage SQL failure still closes connection in finally`() = runTest {
        val connector = FakeJdbcConnector(
            FakeJdbcConnector.Behavior.SqlFailure(
                sqlState = "42000",
                errorCode = 1064,
                message = "You have an error in your SQL syntax",
                atProbe = true,
            ),
        )
        val report = manager(connector).testConnection(config())

        assertFalse(report.healthy)
        assertTrue(report.detail.contains("ErrorCode=1064"))
        assertEquals(1, connector.connectionCloseCalls, "失败路径也必须关闭已建连接")
        assertEquals(0, connector.openConnections)
    }

    // ---------------- 错误映射机制（olap-base） ----------------

    @Test
    fun `dialect classifies structured SQL failures without text matching`() {
        assertEquals(
            OlapErrorCode.PERMISSION_DENIED,
            StarRocksSqlDialect.classify(JdbcFailure("Access denied", "28000", 1045)),
        )
        assertEquals(
            OlapErrorCode.SYNTAX_ERROR,
            StarRocksSqlDialect.classify(JdbcFailure("syntax", "42000", 1064)),
        )
        assertEquals(
            OlapErrorCode.CONNECTION_FAILED,
            StarRocksSqlDialect.classify(JdbcFailure("link failure", "08S01", 0)),
        )
    }

    @Test
    fun `error detail is exposed as a localized ServiceException`() {
        val failure = JdbcFailure("Access denied", "28000", 1045)
        val exception = StarRocksErrorDetail.toServiceException(failure)
        assertEquals(OlapErrorCode.PERMISSION_DENIED.errorKey, exception.errorKey)
        assertEquals(failure, exception.cause)
    }

    // ---------------- healthCheck（基类模板 + S1 钩子） ----------------

    @Test
    fun `healthCheck runs SELECT 1 on shared session and close releases it`() = runTest {
        val connector = FakeJdbcConnector(FakeJdbcConnector.Behavior.Success)
        val manager = manager(connector)

        val noSession = manager.healthCheck()
        assertFalse(noSession.healthy, "未连接时 healthCheck 应直接不健康")

        assertTrue(manager.connect(config()).isSuccess)
        val report = manager.healthCheck()
        assertTrue(report.healthy)
        assertEquals(1, connector.probeCalls)

        manager.shutdown()
        assertEquals(1, connector.connectionCloseCalls)
        assertEquals(1, connector.connectorCloseCalls, "shutdown 应释放连接器")
        assertFalse(manager.isAlive())
    }
}
