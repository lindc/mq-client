package com.mqclient.starrocks.ui

import com.mqclient.core.CoreModule
import com.mqclient.core.event.EventNames
import com.mqclient.olap.base.query.ResultColumn
import com.mqclient.starrocks.StarRocksModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 一次元数据查询的结果（列名 + 行）。刻意只保留轻量结构，不持有
 * `ResultBatch` / 结果集对象 —— 面板用完即丢，不进任何缓存。
 */
internal data class StarRocksQueryRows(
    val columns: List<String>,
    val rows: List<List<Any?>>,
)

/** 一个集群节点（IP / 端口 / 存活 / 版本）。概览与拓扑面板共用。 */
internal data class ClusterNode(
    val ip: String,
    val port: String,
    val alive: Boolean,
    val version: String,
)

/**
 * 集群节点探测语句：优先 StarRocks 的 `SHOW BACKENDS`，不支持则
 * `SHOW FRONTENDS`；都不支持（纯 MySQL）由面板展示空状态。
 * 普通 SQL 错误不会关闭底层连接（见 MysqlJdbcConnection 的中断分类），
 * 因此可以安全地逐条续试。
 */
internal val CLUSTER_NODE_QUERIES = listOf("SHOW BACKENDS;", "SHOW FRONTENDS;")

/** 存活值可能是 true/false、1/0、yes/no，统一归一。 */
private fun parseAlive(raw: Any?): Boolean =
    raw?.toString()?.trim()?.lowercase() in setOf("true", "1", "yes", "y")

/**
 * 按**列名**定位节点字段：StarRocks 的 BACKENDS / FRONTENDS 列名不同，纯 MySQL
 * 又可能返回别的形状。找不到某列时用 "-" 占位，绝不因列缺失崩面板。
 */
internal fun parseNodes(result: StarRocksQueryRows): List<ClusterNode> {
    val cols = result.columns.map { it.lowercase() }
    fun indexOfAny(vararg names: String): Int? =
        names.firstNotNullOfOrNull { n -> cols.indexOf(n.lowercase()).takeIf { it >= 0 } }

    val ipIdx = indexOfAny("ip", "hostname", "host", "backendid", "name")
    val portIdx = indexOfAny("heartbeatport", "queryport", "httpport", "beport", "editlogport", "rpcport", "brpcport")
    val aliveIdx = indexOfAny("alive", "isalive")
    val versionIdx = indexOfAny("version")

    return result.rows.map { row ->
        ClusterNode(
            ip = ipIdx?.let { row.getOrNull(it)?.toString() }.orEmpty().ifBlank { "-" },
            port = portIdx?.let { row.getOrNull(it)?.toString() }.orEmpty().ifBlank { "-" },
            alive = aliveIdx?.let { parseAlive(row.getOrNull(it)) } ?: false,
            version = versionIdx?.let { row.getOrNull(it)?.toString() }.orEmpty().ifBlank { "-" },
        )
    }
}

/** 概览 / 拓扑共用的节点拉取：逐条探测，全部不支持返回空列表。 */
internal suspend fun loadClusterNodes(connectionId: String): List<ClusterNode> {
    var result: StarRocksQueryRows? = null
    for (sql in CLUSTER_NODE_QUERIES) {
        result = runCatching { queryRows(connectionId, sql) }.getOrNull()
        if (result != null && result.rows.isNotEmpty()) break
    }
    return result?.let { parseNodes(it) } ?: emptyList()
}

/**
 * 「数据目录 → SQL 控制台」的跨面板联动 holder（与 app-core 的
 * `TopologyFocusHolder` 同构：pending 供**尚未打开**的面板消费，
 * SharedFlow 供**已打开**的面板实时接收）。
 *
 * 时序：双击表名 → [request] 写入 pending + 广播事件 → 宿主打开 / 切到
 * console Tab → 面板的 LaunchedEffect 先 consume pending，再订阅后续请求。
 * 两条路径都覆盖，不会出现「Tab 刚创建时事件已发过而丢失」。
 */
internal object StarRocksConsoleSqlHolder {
    data class Request(val connectionId: String, val sql: String)

    private val pending = mutableMapOf<String, String>()

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = 8)
    val requests: Flow<Request> = _requests.asSharedFlow()

    fun request(connectionId: String, sql: String) {
        synchronized(pending) { pending[connectionId] = sql }
        _requests.tryEmit(Request(connectionId, sql))
    }

    /** 面板初始化时取走本连接挂起的请求（取后清除，避免重进 Tab 重复应用）。 */
    fun consume(connectionId: String): String? = synchronized(pending) { pending.remove(connectionId) }
}

/** 请求宿主打开（或切到）SQL 控制台 Tab，并把 SQL 交给面板填入编辑器。 */
internal fun openConsoleWithSql(connectionId: String, sql: String) {
    StarRocksConsoleSqlHolder.request(connectionId, sql)
    CoreModule.eventBusOrNull?.emit(
        name = EventNames.OBJECT_TAB_OPEN_REQUESTED,
        payload = mapOf("connectionId" to connectionId, "objectType" to "console"),
        source = "starrocks",
    )
}

/**
 * 面板统一的取数入口：**复用 [StarRocksModule.executeQuery] 的流式通道**，
 * 不另造 JDBC 轮子。元数据结果集通常几十行，一次性收集完即可。
 *
 * 懒加载连接与 SQL 控制台同款（未连接先 `connectById`，连接复用共享会话）。
 * 异常原样上抛，由面板 catch 后显示友好文案。
 */
internal suspend fun queryRows(connectionId: String, sql: String): StarRocksQueryRows {
    if (!StarRocksModule.isAlive()) {
        StarRocksModule.connectById(connectionId).getOrThrow()
    }
    var columns: List<ResultColumn> = emptyList()
    val rows = mutableListOf<List<Any?>>()
    StarRocksModule.executeQuery(connectionId, sql).collect { batch ->
        if (batch.columns.isNotEmpty()) columns = batch.columns
        rows.addAll(batch.rows)
    }
    return StarRocksQueryRows(columns.map { it.name }, rows)
}
