package com.mqclient.starrocks.cluster

import com.mqclient.core.log.LogLevel
import com.mqclient.olap.base.query.ResultColumn
import com.mqclient.starrocks.StarRocksModule
import com.mqclient.starrocks.log.StarRocksLog
import kotlinx.coroutines.CancellationException

/**
 * 集群元数据取数门面（Service 层）：把"要发哪些 SQL、结果怎么归类、哪一块失败可以
 * 忍"全部收在这里，UI 只调 [loadSnapshot] / [loadNode]。
 *
 * ## 依赖方向
 *
 * 拓扑双击 → UI 的控制器 → **本门面**（suspend 调用）→ [StarRocksModule.executeQuery]。
 * 刻意不用 EventBus：事件是"广播 + 无返回值"，做"点了这个节点、拉这一条"的请求-响应
 * 得自己配 correlation id / 超时 / 去重，而一次 suspend 调用就是全部语义。
 * 与 MQ 的属性对话框同款（`ObjectPropertiesDialogController` 直接读 service）。
 *
 * ## 逐块降级（需求 4）
 *
 * 每条语句独立 try/catch，**互不影响**：
 *  - `SHOW FRONTENDS` / `SHOW BACKENDS` 失败 → 记 degradation，另一个照常出图；
 *    两者都失败才真的是空图（普通用户看不到 BACKENDS 是常态）；
 *  - `SHOW COMPUTE NODES` 失败**不**记 degradation（3.0 没有 CN，且"无 CN"与
 *    "不支持"在结果上无差别，记了会永久显示一条无意义提示），只落 DEBUG 日志；
 *  - `SHOW CATALOGS` / `SHOW ROUTINE LOADS` 失败 → 记 degradation（它们是概览
 *    第三层与导入卡片的唯一来源，缺了要能解释）；
 *  - `SHOW PROC '/backends'` 失败 → 记 NODE_MEMORY（内存总量不可得的解释），
 *    概览自动退化到"平均内存使用率"。
 */
object StarRocksClusterService {

    /** 一次查询的原始结果（列名 + 行），只在本文件内部流转。 */
    private data class Rows(val columns: List<String>, val rows: List<List<Any?>>) {
        val isEmpty: Boolean get() = rows.isEmpty()
    }

    private const val SQL_FRONTENDS = "SHOW FRONTENDS;"
    private const val SQL_BACKENDS = "SHOW BACKENDS;"
    private const val SQL_COMPUTE_NODES = "SHOW COMPUTE NODES;"
    private const val SQL_CATALOGS = "SHOW CATALOGS;"

    /** 内存上限 / 已用（裸字节）的唯一 SQL 来源；非管理员会被拒。 */
    private const val SQL_PROC_BACKENDS = "SHOW PROC '/backends';"

    /**
     * Routine Load 探测语句：3.4 用复数形式，老版本 / 变体只认单数。
     * 逐条试、第一条有结果（或表结构可用）就停 —— 与旧版 `CLUSTER_NODE_QUERIES`
     * 的宽松探测同一取向。
     */
    private val ROUTINE_LOAD_PROBES = listOf("SHOW ROUTINE LOADS;", "SHOW ROUTINE LOAD;")

    /**
     * 拉一次完整快照：FE + BE + CN + Catalog + Routine Load + 内存增强。
     *
     * 任何单块失败都不会让本函数抛异常（除协程取消）—— 最坏情况是"只有 FE"，
     * 这正是需求里要求的优雅降级。
     */
    suspend fun loadSnapshot(connectionId: String): ClusterSnapshot {
        ensureConnected(connectionId)
        val degradations = mutableListOf<ClusterDegradation>()

        fun degrade(part: ClusterDataPart, error: Throwable) {
            degradations += ClusterDegradation(
                part = part,
                message = error.message ?: error::class.simpleName ?: "unknown",
            )
        }

        // ---- FE ----
        val frontends = runPart(connectionId, SQL_FRONTENDS) { rows ->
            parseFrontends(rows.columns, rows.rows)
        }.onFailure { degrade(ClusterDataPart.FRONTENDS, it) }.getOrDefault(emptyList())

        // ---- BE / CN ----
        val backends = runPart(connectionId, SQL_BACKENDS) { rows ->
            parseBackends(rows.columns, rows.rows, ClusterNodeRole.BE)
        }.onFailure { degrade(ClusterDataPart.BACKENDS, it) }.getOrDefault(emptyList())

        // CN 失败静默（见类注释）：只记 DEBUG，不进 degradations。
        val computeNodes = runPart(connectionId, SQL_COMPUTE_NODES) { rows ->
            parseBackends(rows.columns, rows.rows, ClusterNodeRole.CN)
        }.onFailure {
            StarRocksLog.sql(
                LogLevel.DEBUG,
                "cluster: SHOW COMPUTE NODES unsupported connection=$connectionId (${it.message})",
            )
        }.getOrDefault(emptyList())

        // ---- Catalog ----
        val catalogs = runPart(connectionId, SQL_CATALOGS) { rows ->
            parseCatalogs(rows.columns, rows.rows)
        }.onFailure { degrade(ClusterDataPart.CATALOGS, it) }.getOrDefault(emptyList())

        // ---- Routine Load（探测式）----
        val routineLoads = loadRoutineLoads(connectionId)
            .onFailure { degrade(ClusterDataPart.ROUTINE_LOADS, it) }
            .getOrDefault(emptyList())

        // ---- 内存增强（PROC）----
        val computeWithMemory = runPart(connectionId, SQL_PROC_BACKENDS) { rows ->
            applyProcBackendMemory(backends + computeNodes, rows.columns, rows.rows)
        }.onFailure { degrade(ClusterDataPart.NODE_MEMORY, it) }
            .getOrDefault(backends + computeNodes)

        val snapshot = ClusterSnapshot(
            nodes = frontends + computeWithMemory + catalogs,
            routineLoads = routineLoads,
            degradations = degradations,
        )
        StarRocksLog.sql(
            "cluster snapshot connection=$connectionId " +
                "fe=${frontends.size} be=${backends.size} cn=${computeNodes.size} " +
                "catalog=${catalogs.size} loads=${routineLoads.size} " +
                "degraded=${degradations.joinToString(",") { it.part.name }}",
        )
        return snapshot
    }

    /**
     * 双击节点的详情刷新：**只重跑该节点所在的那一条语句**，不整轮重扫。
     *
     * 返回 null 表示节点已不在集群里（被摘除 / 改端口）—— 调用方保留快照里的旧数据
     * 并提示"信息可能已过期"，而不是弹一个空对话框。
     */
    suspend fun loadNode(connectionId: String, nodeId: String): ClusterNodeInfo? {
        ensureConnected(connectionId)
        val prefix = nodeId.substringBefore(':', missingDelimiterValue = "")
        return when (prefix) {
            "fe" -> runPart(connectionId, SQL_FRONTENDS) { rows ->
                parseFrontends(rows.columns, rows.rows)
            }.getOrNull()?.firstOrNull { it.nodeId == nodeId }

            "be", "cn" -> {
                val isComputeNode = prefix == "cn"
                val sql = if (isComputeNode) SQL_COMPUTE_NODES else SQL_BACKENDS
                val role = if (isComputeNode) ClusterNodeRole.CN else ClusterNodeRole.BE
                val parsed = runPart(connectionId, sql) { rows ->
                    parseBackends(rows.columns, rows.rows, role)
                }.getOrNull() ?: return null
                val withMemory = runPart(connectionId, SQL_PROC_BACKENDS) { rows ->
                    applyProcBackendMemory(parsed, rows.columns, rows.rows)
                }.getOrDefault(parsed)
                withMemory.firstOrNull { it.nodeId == nodeId }
            }

            "catalog" -> runPart(connectionId, SQL_CATALOGS) { rows ->
                parseCatalogs(rows.columns, rows.rows)
            }.getOrNull()?.firstOrNull { it.nodeId == nodeId }

            else -> null
        }
    }

    /**
     * 与面板既有口径一致：未连接先走 [StarRocksModule.connectById]。
     *
     * 为什么不只依赖 `executeQuery` 内部的懒建连：`connectById` 会 
     * `reportStatus(Connected)`（左侧连接树变绿），而 `executeQuery` 内部的
     * `manager.connect` 只发事件不报状态 —— 直接执行会导致"面板有数据、树还
     * 显示未连接"。这是从旧面板实现里继承下来的教训，迁移时不能丢。
     */
    private suspend fun ensureConnected(connectionId: String) {
        if (!StarRocksModule.isAlive(connectionId)) {
            StarRocksModule.connectById(connectionId).getOrThrow()
        }
    }

    /** 探测式拉 Routine Load：第一条返回结果（或至少能执行）的语句胜出。 */
    private suspend fun loadRoutineLoads(connectionId: String): Result<List<RoutineLoadInfo>> {
        var lastError: Throwable? = null
        for (sql in ROUTINE_LOAD_PROBES) {
            val result = runPart(connectionId, sql) { rows ->
                parseRoutineLoads(rows.columns, rows.rows)
            }
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
            StarRocksLog.sql(
                LogLevel.DEBUG,
                "cluster: routine load probe failed connection=$connectionId sql=$sql " +
                    "error=${lastError?.message}",
            )
        }
        return Result.failure(lastError ?: IllegalStateException("SHOW ROUTINE LOADS unsupported"))
    }

    /**
     * 执行一条 SQL 并解析：SQL 失败与解析失败都收敛成 [Result.failure]，
     * 由调用方决定"记降级"还是"静默忽略"。取消异常照旧上抛（不能吞）。
     */
    private suspend fun <T> runPart(
        connectionId: String,
        sql: String,
        parse: (Rows) -> T,
    ): Result<T> {
        val startedAt = System.currentTimeMillis()
        StarRocksLog.sql(LogLevel.DEBUG, "cluster exec connection=$connectionId ${StarRocksLog.sqlSummary(sql)}")
        return try {
            var columns: List<ResultColumn> = emptyList()
            val rows = mutableListOf<List<Any?>>()
            StarRocksModule.executeQuery(connectionId, sql).collect { batch ->
                if (batch.columns.isNotEmpty()) columns = batch.columns
                rows.addAll(batch.rows)
            }
            val cost = System.currentTimeMillis() - startedAt
            StarRocksLog.sql(
                "cluster done connection=$connectionId type=${StarRocksLog.statementKind(sql)} " +
                    "rows=${rows.size} columns=${columns.size} cost=${cost}ms",
            )
            StarRocksLog.perf(
                action = "cluster_query",
                type = StarRocksLog.statementKind(sql),
                costMs = cost,
            )
            Result.success(parse(Rows(columns.map { it.name }, rows)))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            val cost = System.currentTimeMillis() - startedAt
            StarRocksLog.sql(
                LogLevel.WARN,
                "cluster failed connection=$connectionId type=${StarRocksLog.statementKind(sql)} " +
                    "cost=${cost}ms error=${error.message}",
                error,
            )
            Result.failure(error)
        }
    }
}
