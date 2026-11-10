package com.mqclient.olap.base.query

import com.mqclient.olap.base.query.QueryAuditRecord
import com.mqclient.olap.base.audit.QueryAuditSink
import com.mqclient.olap.base.connection.AnalyticsSession
import com.mqclient.olap.base.error.OlapErrorCode
import com.mqclient.olap.base.perf.olapTimeMillis
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 钉死查询执行器模板的核心契约：**取消幂等且线程安全**、审计终态只写一次、
 * 流失败按方言分类进审计。全部用 fake 实现，不依赖真实数据库。
 */
class AbstractQueryExecutorTest {

    /** 可编程的 fake 执行器：流行为按构造参数切换，取消与审计计数可断言。 */
    private class FakeExecutor(
        auditSink: QueryAuditSink,
        private val streamFailure: Throwable? = null,
    ) : AbstractQueryExecutor<FakeSession>(auditSink) {

        var cancelCalls: Int = 0
        val batches = listOf(
            ResultBatch(listOf(ResultColumn("id", "UInt64")), listOf(listOf(1L), listOf(2L)), false, 0),
            ResultBatch(listOf(ResultColumn("id", "UInt64")), listOf(listOf(3L)), true, 1),
        )

        override suspend fun doExecute(session: FakeSession, query: AnalyticsQuery): QueryHandle =
            QueryHandle(id = "q-1", sql = query.sql, startedAt = olapTimeMillis(), readOnly = query.readOnly)

        override fun doStream(handle: QueryHandle): Flow<ResultBatch> = flow {
            batches.forEach { batch ->
                streamFailure?.let { throw it }
                emit(batch)
            }
        }

        override suspend fun doCancel(handle: QueryHandle) {
            cancelCalls++
        }

        override suspend fun doExplain(session: FakeSession, query: AnalyticsQuery): PlanNode =
            PlanNode("Seq Scan")

        override fun classifyError(error: Throwable): OlapErrorCode = OlapErrorCode.TIMEOUT
    }

    /** 最小 fake 会话（执行器不真正使用它，只为满足类型参数）。 */
    private class FakeSession : AnalyticsSession {
        var closed = false
        override suspend fun close() {
            closed = true
        }

        override fun isUsable(): Boolean = true
    }

    /** 收集审计记录的 sink（可断言终态只写一次）。 */
    private class RecordingSink : QueryAuditSink {
        val records = mutableListOf<QueryAuditRecord>()
        override suspend fun onQueryFinished(record: QueryAuditRecord) {
            records += record
        }
    }

    @Test
    fun `cancel is idempotent - second cancel does not reach protocol`() = runTest {
        val executor = FakeExecutor(QueryAuditSink.Noop)
        val session = FakeSession()
        val handle = executor.execute(session, AnalyticsQuery(sql = "SELECT 1"))

        assertTrue(executor.cancel(handle), "第一次 cancel 应发起底层取消")
        assertFalse(executor.cancel(handle), "重复 cancel 不应再次触发底层取消")
        assertEquals(1, executor.cancelCalls, "协议侧取消必须只执行一次")
    }

    @Test
    fun `cancel on unknown handle returns false without touching protocol`() = runTest {
        val executor = FakeExecutor(QueryAuditSink.Noop)
        val ghost = QueryHandle(id = "ghost", sql = "SELECT 1", startedAt = 0, readOnly = true)

        assertFalse(executor.cancel(ghost))
        assertEquals(0, executor.cancelCalls)
    }

    @Test
    fun `cancel on completed stream returns false`() = runTest {
        val sink = RecordingSink()
        val executor = FakeExecutor(sink)
        val session = FakeSession()
        val handle = executor.execute(session, AnalyticsQuery(sql = "SELECT 1"))

        executor.stream(handle).toList()
        assertFalse(executor.cancel(handle), "已完成的查询不应再可取消")
        assertEquals(0, executor.cancelCalls)
    }

    @Test
    fun `completed stream writes single COMPLETED audit record with row count`() = runTest {
        val sink = RecordingSink()
        val executor = FakeExecutor(sink)
        val session = FakeSession()
        val handle = executor.execute(session, AnalyticsQuery(sql = "SELECT 1"))

        val batches = executor.stream(handle).toList()
        assertEquals(2, batches.size)
        assertTrue(batches.last().lastBatch)

        assertEquals(1, sink.records.size, "终态审计只写一次")
        val record = sink.records.single()
        assertEquals(QueryState.COMPLETED, record.status)
        assertEquals(3L, record.rowsFetched)
        assertEquals(null, record.errorCode)
    }

    @Test
    fun `failed stream writes FAILED audit with dialect classification`() = runTest {
        val sink = RecordingSink()
        val executor = FakeExecutor(sink, streamFailure = IllegalStateException("query timed out"))
        val session = FakeSession()
        val handle = executor.execute(session, AnalyticsQuery(sql = "SELECT 1"))

        // 流失败 = 原异常向收集方传播（onCompletion 不吞）；审计先行收尾。
        // assertFailsWith 的 block 非 suspend，挂起的 toList 用 try/catch 接。
        val thrown: IllegalStateException? = try {
            executor.stream(handle).toList()
            null
        } catch (error: IllegalStateException) {
            error
        }
        assertEquals("query timed out", assertNotNull(thrown).message)

        assertEquals(1, sink.records.size)
        val record = sink.records.single()
        assertEquals(QueryState.FAILED, record.status)
        assertEquals(OlapErrorCode.TIMEOUT, record.errorCode, "失败必须经方言分类进审计")
    }

    @Test
    fun `cancelled stream writes CANCELLED audit once`() = runTest {
        val sink = RecordingSink()
        val executor = FakeExecutor(sink)
        val session = FakeSession()
        val handle = executor.execute(session, AnalyticsQuery(sql = "SELECT 1"))

        assertTrue(executor.cancel(handle))
        assertEquals(1, sink.records.size)
        assertIs<QueryAuditRecord>(sink.records.single()).let { record ->
            assertEquals(QueryState.CANCELLED, record.status)
        }
    }

    @Test
    fun `explain returns plan node`() = runTest {
        val executor = FakeExecutor(QueryAuditSink.Noop)
        val plan = executor.explain(FakeSession(), AnalyticsQuery(sql = "SELECT 1"))
        assertEquals("Seq Scan", plan.name)
    }
}
