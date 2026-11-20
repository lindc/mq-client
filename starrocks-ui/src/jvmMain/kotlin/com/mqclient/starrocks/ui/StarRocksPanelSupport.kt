package com.mqclient.starrocks.ui

import com.mqclient.core.CoreModule
import com.mqclient.core.event.EventNames
import com.mqclient.core.log.LogLevel
import com.mqclient.olap.base.query.ResultColumn
import com.mqclient.starrocks.StarRocksModule
import com.mqclient.starrocks.log.StarRocksLog
import kotlinx.coroutines.CancellationException
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

/**
 * 字节 → 人话（1024 进制，与 `SHOW BACKENDS` 的显示口径一致）。
 *
 * 实现不用 `String.format` / `Locale`：这是纯数值格式化，手写取整避免引入平台差异。
 */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    // 大单位保留 1 位小数（1.2 GB）；大到三位数时小数没意义，直接取整。
    val rounded = if (value >= 100) value.toLong().toDouble() else kotlin.math.round(value * 10) / 10
    val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    return "$text ${units[unitIndex]}"
}

/**
 * 百分比展示：`0.53 %` → `0.53%`（小于 1% 保留 2 位，否则 1 位；≥10% 取整）。
 * 保留小数是因为 StarRocks 的 CPU 使用率常态就是个位数。
 */
internal fun formatPercent(percent: Double): String {
    val text = when {
        percent >= 10 -> kotlin.math.round(percent).toLong().toString()
        percent >= 1 -> (kotlin.math.round(percent * 10) / 10).toString()
        else -> (kotlin.math.round(percent * 100) / 100).toString()
    }
    return "$text%"
}

/** 两数比百分比（分母无效时返回空串，调用方据此不渲染）。 */
internal fun percentOf(used: Long, total: Long): String =
    if (total <= 0) "" else formatPercent(used.toDouble() * 100.0 / total.toDouble())

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
    val start = System.currentTimeMillis()
    // 面板层的每次取数都过这里（目录 / 进程列表 / Profile / 导出）：
    // 一句 INFO 就能回答「这个面板到底发了什么 SQL」。
    StarRocksLog.sql(
        LogLevel.DEBUG,
        "panel exec connection=$connectionId ${StarRocksLog.sqlSummary(sql)}",
    )
    // 预检只是加速路径：会话不属于该连接 / 未连接时，executeQuery 内部会自动抢占建连。
    if (!StarRocksModule.isAlive(connectionId)) {
        StarRocksModule.connectById(connectionId).getOrThrow()
    }
    var columns: List<ResultColumn> = emptyList()
    val rows = mutableListOf<List<Any?>>()
    try {
        StarRocksModule.executeQuery(connectionId, sql).collect { batch ->
            if (batch.columns.isNotEmpty()) columns = batch.columns
            rows.addAll(batch.rows)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        // 元数据 / Profile 取数失败会直接弹在面板上：WARN，并附「原列名 + 首个错误行」
        // 这类形状信息（没有结果集时只能靠错误本身）。
        StarRocksLog.sql(
            LogLevel.WARN,
            "panel exec failed connection=$connectionId " +
                "cost=${System.currentTimeMillis() - start}ms " +
                "type=${StarRocksLog.statementKind(sql)} error=${error.message}",
            error,
        )
        StarRocksLog.perf(
            action = "panel_query",
            type = StarRocksLog.statementKind(sql),
            costMs = System.currentTimeMillis() - start,
        )
        throw error
    }
    val cost = System.currentTimeMillis() - start
    StarRocksLog.sql(
        "panel done connection=$connectionId type=${StarRocksLog.statementKind(sql)} " +
            "rows=${rows.size} columns=${columns.size} cost=${cost}ms",
    )
    StarRocksLog.perf(
        action = "panel_query",
        type = StarRocksLog.statementKind(sql),
        costMs = cost,
    )
    return StarRocksQueryRows(columns.map { it.name }, rows)
}
