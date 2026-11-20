package com.mqclient.starrocks.query

import com.mqclient.core.log.LogLevel
import com.mqclient.olap.base.query.ResultColumn
import com.mqclient.starrocks.StarRocksModule
import com.mqclient.starrocks.log.StarRocksLog
import kotlinx.coroutines.CancellationException

/**
 * StarRocks 的**唯一取数入口**：面板、目录、Profile、集群快照全部走这里。
 *
 * ## 为什么必须只有一个
 *
 * 在此之前模块里有三份几乎逐字相同的实现（UI 的 `queryRows`、系统页的
 * `StarRocksSystemService.query`、集群快照的 `runPart`），外加五处各自手写的
 * 「未连接就先连」守卫。它们的差别全在**细节**上：有的记 SQL 日志、有的不记；
 * 有的把取消上抛、有的被 `runCatching` 吞掉。同一件事有三种行为，排查时无法
 * 用一句话描述「这个模块怎么取数」。
 *
 * ## 连接守卫为什么不能省
 *
 * `executeQuery` 内部也会在有需要时懒建连，但它只发事件、**不** `reportStatus` ——
 * 于是出现「面板有数据，左侧连接树还显示未连接」。这里统一走
 * [StarRocksModule.connectById]（会 `reportStatus(Connected)`），与 SQL 控制台
 * 同一条路径。这条经验原先是散在 ClusterService 注释里的，现在由代码本身保证。
 *
 * ## 异常语义
 *
 * 取消（`CancellationException`）与 `Error` 原样上抛，其余异常记 WARN 后上抛 ——
 * 由调用方决定是显示错误态还是降级成空结果。绝不在这里吞掉。
 */

/**
 * 连接守卫：保证 [connectionId] 的共享会话可用。
 *
 * 单独暴露是为了**流式**调用方（SQL 控制台直接消费 `executeQuery`，不经过
 * [queryRows]）也能复用同一条规则。规则本身只有一句：
 * `未连接 / 会话属于别的连接 → connectById`，因为只有它
 * 会 `reportStatus(Connected)`，左侧连接树才会变绿。
 *
 * @param onConnecting 即将建连时回调（控制台据此切到「正在建立连接…」阶段）。
 * @return 建连结果；会话已可用时直接 `Result.success(Unit)`。
 */
suspend fun ensureSession(
    connectionId: String,
    onConnecting: suspend () -> Unit = {},
): Result<Unit> {
    if (StarRocksModule.isAlive(connectionId)) return Result.success(Unit)
    onConnecting()
    return StarRocksModule.connectById(connectionId)
}

/**
 * 在当前共享会话上执行一条只读 SQL，把结果一次性收集完。
 *
 * 元数据类结果集通常几十到几百行，一次性收集是有意为之 —— 面板要么全显示、
 * 要么不显示，流式只对 SQL 控制台的大结果有意义（那条路径直接消费
 * [StarRocksModule.executeQuery]，不经过这里）。
 *
 * @param origin 调用方标识（`panel` / `cluster` / `system`），进日志与打点 action
 *   （`panel_query` / `cluster_query` / `system_query`）—— 保留来源维度才能回答
 *   「哪条通道慢」，合并成单一 `query` 会丢掉这个区分。
 */
suspend fun queryRows(
    connectionId: String,
    sql: String,
    origin: String = "panel",
): QueryRows {
    val start = System.currentTimeMillis()
    val kind = StarRocksLog.statementKind(sql)
    StarRocksLog.sql(
        LogLevel.DEBUG,
        "$origin exec connection=$connectionId ${StarRocksLog.sqlSummary(sql)}",
    )

    // 未连接 / 会话属于别的连接：先建连（规则见 [ensureSession]）。
    ensureSession(connectionId).getOrThrow()

    var columns: List<ResultColumn> = emptyList()
    val rows = mutableListOf<List<Any?>>()
    try {
        StarRocksModule.executeQuery(connectionId, sql).collect { batch ->
            if (batch.columns.isNotEmpty()) columns = batch.columns
            rows.addAll(batch.rows)
        }
    } catch (cancellation: CancellationException) {
        StarRocksLog.sql(
            LogLevel.DEBUG,
            "$origin cancelled connection=$connectionId type=$kind " +
                "cost=${System.currentTimeMillis() - start}ms rows=${rows.size}",
        )
        throw cancellation
    } catch (error: Throwable) {
        if (error is Error) throw error
        StarRocksLog.sql(
            LogLevel.WARN,
            "$origin failed connection=$connectionId type=$kind " +
                "cost=${System.currentTimeMillis() - start}ms error=${error.message}",
            error,
        )
        StarRocksLog.perf(action = "${origin}_query", type = kind, costMs = System.currentTimeMillis() - start)
        throw error
    }

    val cost = System.currentTimeMillis() - start
    StarRocksLog.sql(
        "$origin done connection=$connectionId type=$kind " +
            "rows=${rows.size} columns=${columns.size} cost=${cost}ms",
    )
    StarRocksLog.perf(action = "${origin}_query", type = kind, costMs = cost)
    return QueryRows(columns.map { it.name }, rows)
}
