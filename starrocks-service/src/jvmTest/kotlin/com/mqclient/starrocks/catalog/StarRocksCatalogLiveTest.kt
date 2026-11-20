package com.mqclient.starrocks.catalog

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionRegistry
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import com.mqclient.starrocks.StarRocksConnectionConfig
import com.mqclient.starrocks.StarRocksModule
import com.mqclient.starrocks.query.queryRows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 数据目录 SQL + 解析的**真机**校验：整条链路（拼 SQL → 走共享会话 → 解析）
 * 在真实 StarRocks 上跑一遍。
 *
 * 这些逻辑刚从 UI 面板下沉到 service，单测只覆盖「给定结果集怎么解析」；
 * 这里补上「真实集群返回的结果集能不能被解析出预期数据」。与其它 `*LiveTest`
 * 同一约定：**环境不就绪就跳过**，CI 上没有本地集群也不会红。
 *
 * ```
 * ./gradlew :starrocks-service:jvmTest --tests '*CatalogLiveTest' \
 *   -Dstarrocks.live.enabled=true -Dstarrocks.live.password=...
 * ```
 */
class StarRocksCatalogLiveTest {

    private val enabled = System.getProperty("starrocks.live.enabled") == "true"
    private val host = System.getProperty("starrocks.live.host") ?: "127.0.0.1"
    private val port = System.getProperty("starrocks.live.port")?.toIntOrNull() ?: 9030
    private val user = System.getProperty("starrocks.live.user") ?: "root"
    private val password = System.getProperty("starrocks.live.password") ?: ""
    private val connectionId = "live-catalog"

    /** 夹具库 / 表：由验证环境预置（当前本地集群有 test_db.orders，11 列）。 */
    private val database = System.getProperty("starrocks.live.database") ?: "test_db"
    private val table = System.getProperty("starrocks.live.table") ?: "orders"

    @AfterTest
    fun tearDown() {
        runCatching { StarRocksModule.dispose() }
    }

    private fun installModule() {
        val scope = CoroutineScope(SupervisorJob())
        val bus = EventBus(scope)
        val config = StarRocksConnectionConfig(
            connectionId = connectionId,
            displayName = "live",
            host = host,
            port = port,
            user = user,
            password = password,
            database = database,
        )
        val info = ConnectionInfo(
            id = connectionId,
            name = "live",
            serviceId = "starrocks",
            mqName = "",
            host = host,
            port = port,
            options = config.options,
        )
        val registry = object : ConnectionRegistry {
            private val state = MutableStateFlow(listOf(info))
            override val connections: StateFlow<List<ConnectionInfo>> = state.asStateFlow()
            override fun findConnection(connectionId: String): ConnectionInfo? =
                info.takeIf { it.id == connectionId }
            override fun reportStatus(connectionId: String, status: ConnectionStatus, source: String) = true
        }
        StarRocksModule.initialize(
            ServiceContext.of("starrocks", bus, scope, JobScheduler(scope, bus), registry),
        )
    }

    @Test
    fun `table list is fetched and parsed`() = runBlocking {
        if (!enabled) return@runBlocking
        installModule()
        val raw = queryRows(connectionId, TABLES_SQL, origin = "live")
        val tables = parseTables(raw, mvNames = emptySet())
        println("LIVE catalog tables=${tables.map { it.name }} rows=${raw.rows.size}")

        assertTrue(tables.isNotEmpty(), "表清单不应为空（库 $database 里应有表）")
        assertTrue(tables.any { it.name == table }, "应包含夹具表 $table")
        assertEquals(raw.rows.size, tables.size, "真实数据不应触发坏行丢弃")
    }

    @Test
    fun `materialized view names probe does not throw`() = runBlocking {
        if (!enabled) return@runBlocking
        installModule()
        // MySQL 不支持该语句会抛错；StarRocks 上应返回可解析的结果集（可能为空集）。
        val names = runCatching {
            parseMaterializedViewNames(queryRows(connectionId, MATERIALIZED_VIEW_SQL, origin = "live"))
        }
        println("LIVE catalog mvNames=${names.getOrNull()}")
        assertTrue(names.isSuccess, "StarRocks 上 SHOW MATERIALIZED VIEWS 应可执行：${names.exceptionOrNull()}")
    }

    @Test
    fun `columns are fetched and parsed with all seven attributes`() = runBlocking {
        if (!enabled) return@runBlocking
        installModule()
        val raw = queryRows(connectionId, columnsSql(table), origin = "live")
        val columns = parseColumns(raw)
        println("LIVE catalog columns=${columns.map { "${it.name}:${it.type}" }}")

        assertTrue(columns.size >= 5, "夹具表应有若干字段，实际 ${columns.size}")
        assertTrue(columns.any { it.name == "id" }, "应包含 id")
        assertTrue(columns.all { it.type.isNotBlank() }, "每列都应有类型")
        assertTrue(columns.any { it.comment.isNotBlank() }, "夹具表带列注释，应被解析出来")
        // nullable / key / default / extra 四列在 3.4 的 information_schema 里都存在，
        // 至少有一列是非空取值（否则说明列名绑定错了）。
        assertTrue(
            columns.any { it.nullable.isNotBlank() || it.key.isNotBlank() || it.extra.isNotBlank() },
            "nullable / key / extra 至少一列应有值，否则列名绑定可能错了",
        )
    }

    @Test
    fun `ddl is fetched`() = runBlocking {
        if (!enabled) return@runBlocking
        installModule()
        val rows = queryRows(connectionId, ddlSql(table), origin = "live")
        val ddl = rows.rows.firstOrNull()?.getOrNull(1)?.toString().orEmpty()
        println("LIVE catalog ddlChars=${ddl.length}")
        assertTrue(ddl.contains("CREATE TABLE", ignoreCase = true), "DDL 应包含 CREATE TABLE：${ddl.take(120)}")
    }

    @Test
    fun `preview sql returns rows`() = runBlocking {
        if (!enabled) return@runBlocking
        installModule()
        // 夹具表插过 10000 行；预览语句限 100 行。
        val rows = queryRows(connectionId, previewSql(table), origin = "live")
        println("LIVE catalog preview rows=${rows.rows.size} columns=${rows.columns.size}")
        assertTrue(rows.columns.isNotEmpty(), "预览应带回列名")
        assertTrue(rows.rows.isNotEmpty(), "夹具表非空，预览应带回数据")
        assertTrue(rows.rows.size <= 100, "预览语句应被 LIMIT 100 截断，实际 ${rows.rows.size}")
    }
}
