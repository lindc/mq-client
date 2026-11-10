package com.mqclient.olap.base.query

import com.mqclient.core.log.Log
import com.mqclient.olap.base.OlapCategory.PERF_TAG_PREFIX
import com.mqclient.olap.base.audit.QueryAuditSink
import com.mqclient.olap.base.connection.AnalyticsSession
import com.mqclient.olap.base.error.OlapErrorCode
import com.mqclient.olap.base.perf.olapTimeMillis
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * OLAP 查询执行的模板基类：提交 → 流式取回 → 取消 / 审计的统一骨架。
 *
 * ## 句柄隔离规范（与 MQ 系同一口径）
 *
 * [QueryHandle] / [ResultBatch] 是**轻量句柄**：即用即毁，绝不跨请求复用 ——
 * 一次 poll 异常会污染内部状态（Kafka 那轮的真实教训：跨请求复用 Consumer 让
 * 一次失败拖垮后续所有读取）。执行器内部维护活跃句柄表（状态转移加锁），取消
 * **幂等且线程安全**：并发 / 重复 cancel 只产生一次底层取消请求。
 *
 * ## 审计
 *
 * 每次查询终态（完成 / 失败 / 取消）写一条 [QueryAuditRecord] 到注入的
 * [QueryAuditSink]（app-core 据此落库查询历史）。取消按 [QueryState.CANCELLED]
 * 记录；失败按方言 [classifyError] 的分类记录。审计写在**状态从 RUNNING 转出**
 * 的那次操作里（stream 收尾或 cancel）——只写一次。
 *
 * ## 性能打点
 *
 * `[OLAP-Perf][execute]` / `[plan]` / `[fetch]` 分段，分段口径与 `[MQ-Perf]` 的
 * instance / io / parse / total 对齐（execute = 提交，fetch = 拉流，plan = EXPLAIN）。
 *
 * ## 并发模型
 *
 * 活跃句柄表的状态转移用 [tableMutex] 串行化（`kotlin.synchronized` 在 commonMain
 * 不可用，JVM 专属）；行数计数用 [AtomicLong]（onEach 的 lambda 里不做挂起操作，
 * 流算子内免锁）。[finalize] 只在状态仍在活跃表（RUNNING）时生效 —— [cancel] 先行
 * 收尾后这里拿不到条目，天然不重复。
 *
 * @param S 会话类型。
 * @param auditSink 审计回调（默认 Noop）。
 */
abstract class AbstractQueryExecutor<S : AnalyticsSession>(
    private val auditSink: QueryAuditSink = QueryAuditSink.Noop,
) {

    private val log = Log.withTag("olap-query")

    /** 活跃句柄表：id → 状态引用。所有状态转移经 [tableMutex] 串行（取消幂等的保证）。 */
    private val tableMutex = Mutex()
    private val activeQueries = LinkedHashMap<String, ActiveQuery>()

    /** 一条在途查询的状态引用；行数用原子计数（流算子内免锁累加）。 */
    private class ActiveQuery(val handle: QueryHandle) {
        @Volatile
        var state: QueryState = QueryState.RUNNING

        val rowsFetched = AtomicLong()
    }

    /* ------------------------------ 子类钩子 ------------------------------ */

    /** 提交查询（协议侧返回可取消的句柄）。已提交但未取流的句柄也要登记。 */
    protected abstract suspend fun doExecute(session: S, query: AnalyticsQuery): QueryHandle

    /** 流式取回一个已提交句柄的结果批次（直到末页；失败 / 取消时以异常 / 取消结束）。 */
    protected abstract fun doStream(handle: QueryHandle): Flow<ResultBatch>

    /** 执行协议侧取消（只会被调一次 —— 幂等性由本基类保证）。 */
    protected abstract suspend fun doCancel(handle: QueryHandle)

    /** 生成结构化查询计划。 */
    protected abstract suspend fun doExplain(session: S, query: AnalyticsQuery): PlanNode

    /** 协议错误 → [OlapErrorCode]（审计用）。默认 UNKNOWN；协议模块按错误码覆盖。 */
    protected open fun classifyError(error: Throwable): OlapErrorCode = OlapErrorCode.UNKNOWN

    /* ------------------------------ 公开 API ------------------------------ */

    /**
     * 提交查询：打 `[execute]` 分段、登记活跃句柄。只读查询（[AnalyticsQuery.readOnly]）
     * 由协议实现保证执行路径只读 —— 本基类无法在提交前做语法级校验。
     */
    suspend fun execute(session: S, query: AnalyticsQuery): QueryHandle {
        val start = olapTimeMillis()
        val handle = doExecute(session, query)
        tableMutex.withLock { activeQueries[handle.id] = ActiveQuery(handle) }
        log.info(
            "$PERF_TAG_PREFIX[execute] queryId=${handle.id} 耗时=${olapTimeMillis() - start}ms " +
                "readOnly=${query.readOnly}",
        )
        return handle
    }

    /**
     * 流式取回结果：行数原子累加；终态写审计（完成 / 失败 / 取消）。
     *
     * 取消（[cancel]）会让底层流抛取消型异常 —— 识别为 [QueryState.CANCELLED]；
     * 但 cancel 先于流收尾时审计已由 [cancel] 写过（本函数不重复）。
     */
    fun stream(handle: QueryHandle): Flow<ResultBatch> = doStream(handle)
        .onEach { batch ->
            activeQueries[handle.id]?.rowsFetched?.addAndGet(batch.rows.size.toLong())
        }
        .onCompletion { cause -> finalize(handle, cause) }

    /**
     * 取消一个在途查询。**幂等且线程安全**：只有第一次调用把状态转成 CANCELLED
     * 并触发 [doCancel]；对不存在 / 已终态的句柄返回 false 且不做任何事。
     *
     * 审计立即写（查询可能已提交但流尚未被收集 —— 审计不能等一个也许永远不来的
     * onCompletion）。
     *
     * @return 是否发生了本次调用触发的取消（true = 底层取消已发起）。
     */
    suspend fun cancel(handle: QueryHandle): Boolean {
        val record: QueryAuditRecord? = tableMutex.withLock {
            val active = activeQueries.remove(handle.id) ?: return false
            if (active.state != QueryState.RUNNING) return false
            active.state = QueryState.CANCELLED
            buildRecord(active, handle, cause = null)
        }
        log.info("query cancelled: queryId=${handle.id}")
        doCancel(handle)
        record?.let { auditSink.onQueryFinished(it) }
        return true
    }

    /**
     * 生成查询计划：打 `[plan]` 分段。失败经 [classifyError] 分类后原样上抛
     * （EXPLAIN 失败与执行失败同因，分类供上层统一文案）。
     */
    suspend fun explain(session: S, query: AnalyticsQuery): PlanNode {
        val start = olapTimeMillis()
        try {
            return doExplain(session, query)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            log.warn(
                "$PERF_TAG_PREFIX[plan] explain failed: error=${classifyError(error)} " +
                    "耗时=${olapTimeMillis() - start}ms",
            )
            throw error
        }
    }

    /* ------------------------------ 内部 ------------------------------ */

    /**
     * 终态收尾：状态落定、摘除活跃表、写审计（一次）。
     *
     * 只在状态仍在活跃表（RUNNING）时生效 —— [cancel] 已先行收尾（CANCELLED）的话
     * 这里拿不到条目，自然不重复。
     */
    private suspend fun finalize(handle: QueryHandle, cause: Throwable?) {
        val record: QueryAuditRecord? = tableMutex.withLock {
            val active = activeQueries.remove(handle.id) ?: return@withLock null
            when (cause) {
                null -> active.state = QueryState.COMPLETED
                is CancellationException -> active.state = QueryState.CANCELLED
                else -> active.state = QueryState.FAILED
            }
            buildRecord(active, handle, cause)
        }
        if (record != null) {
            log.info(
                "$PERF_TAG_PREFIX[fetch] queryId=${handle.id} state=${record.status} " +
                    "rows=${record.rowsFetched} 耗时=${record.durationMs}ms",
            )
            auditSink.onQueryFinished(record)
        }
        // 原异常（失败 / 取消）由 onCompletion 自动继续向收集方传播，这里不手动
        // 重抛 —— 手动重抛会让 CANCELLED 场景的底层正常结束也变成异常。
    }

    /** 构造终态审计记录（[finalize] 与 [cancel] 共用）。 */
    private fun buildRecord(
        active: ActiveQuery,
        handle: QueryHandle,
        cause: Throwable?,
    ): QueryAuditRecord = QueryAuditRecord(
        queryId = handle.id,
        connectionId = "",
        sql = handle.sql,
        startedAt = handle.startedAt,
        durationMs = olapTimeMillis() - handle.startedAt,
        status = active.state,
        rowsFetched = active.rowsFetched.get(),
        errorCode = cause?.let { classifyError(it) },
    )
}
