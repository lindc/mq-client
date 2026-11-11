package com.mqclient.olap.base.query

import com.mqclient.olap.base.error.OlapErrorCode

/**
 * 一条待执行的 OLAP 查询。
 *
 * @param sql 查询文本（协议方言由 `SqlDialect` 在执行前加工，本类型只存原文）。
 * @param parameters 绑定参数（按 `?` 占位序；协议不支持绑定参数时由执行器内联，
 *   只读标志为 true 时**禁止**内联 —— 参数必须走协议绑定，防注入）。
 * @param timeoutMs 查询超时（毫秒）；null = 不单独设超时（由会话层默认值决定）。
 * @param readOnly 是否只读会话（true 时执行器必须走只读路径 —— 拒绝 DML/DDL）。
 * @param connectionId 查询所属连接（审计记录用）。
 */
data class AnalyticsQuery(
    val sql: String,
    val parameters: List<Any?> = emptyList(),
    val timeoutMs: Long? = null,
    val readOnly: Boolean = true,
    val connectionId: String = "",
)

/**
 * 一次执行的句柄（轻量、即用即毁）。
 *
 * 状态机由执行器内部维护（本类保持不可变），取消是幂等操作 —— 重复 cancel
 * 不产生第二次底层取消请求（契约见 `AbstractQueryExecutor.cancel`）。
 *
 * @param id 句柄唯一 id（协议侧 queryId / 生成的 UUID），取消与审计按它索引。
 * @param sql 执行的查询文本（审计用快照）。
 * @param startedAt 执行开始时刻（epoch 毫秒）。
 * @param readOnly 是否只读查询。
 */
data class QueryHandle(
    val id: String,
    val sql: String,
    val startedAt: Long,
    val readOnly: Boolean,
)

/** 查询生命周期状态。 */
enum class QueryState {
    /** 已提交、尚未取完结果。 */
    RUNNING,

    /** 正常取完。 */
    COMPLETED,

    /** 被取消（幂等：只允许 RUNNING → CANCELLED 一次转移）。 */
    CANCELLED,

    /** 执行失败（错误码经方言分类）。 */
    FAILED,
}

/** 结果批次的一列 schema。 */
data class ResultColumn(
    val name: String,
    /** 类型标签（协议原文，如 `UInt64` / `Nullable(String)` / `DECIMAL(18,2)`）。 */
    val typeLabel: String,
)

/**
 * 一个结果批次（流式返回的分片）。
 *
 * 行数据是 `List<List<Any?>>`：协议侧负责把原生值映射成 Kotlin 基本类型
 * （Long / Double / String / Boolean / null），不做跨协议统一类型系统。
 *
 * @param columns 列 schema（每个批次都带全量 schema —— 分片自包含，UI 分页渲染
 *   不需要回看前序批次）。
 * @param rows 行数据。
 * @param lastBatch 是否末页。
 * @param batchIndex 批次序号（从 0 起）。
 * @param updateCount DML / DDL 的受影响行数（仅非查询语句；SELECT 流为 null）。
 *   携带在批次上而不是单独的类型：协议侧一条语句要么返回结果集流、要么返回
 *   单个受影响行数，UI 按此字段分流渲染。
 */
data class ResultBatch(
    val columns: List<ResultColumn>,
    val rows: List<List<Any?>>,
    val lastBatch: Boolean,
    val batchIndex: Int,
    val updateCount: Long? = null,
)

/**
 * 查询计划树（EXPLAIN 结果的结构化形态）。
 *
 * 树形建模而不是字符串：将来 topology-ui / 计划可视化可以直接按 [children] 递归
 * 渲染；`detail` 保留协议原文片段（成本估算、行数估计等）。
 *
 * @param name 节点操作名（`Seq Scan` / `Aggregate` / `ReadFromMergeTree`…）。
 * @param detail 协议侧的附加信息原文。
 * @param children 子计划（按执行顺序）。
 */
data class PlanNode(
    val name: String,
    val detail: String? = null,
    val children: List<PlanNode> = emptyList(),
)

/**
 * 查询审计记录（[com.mqclient.olap.base.audit.QueryAuditSink] 的载荷）。
 *
 * @param queryId 句柄 id。
 * @param connectionId 所属连接。
 * @param sql 查询文本（参数不内联 —— 审计里只存原文，避免把凭据类参数落盘）。
 * @param startedAt 开始时刻（epoch 毫秒）。
 * @param durationMs 总耗时（毫秒）。
 * @param status 终态（COMPLETED / CANCELLED / FAILED；RUNNING 不会出现在审计里）。
 * @param rowsFetched 取回的总行数（失败 / 取消时为已完成的部分计数）。
 * @param errorCode 失败时的错误分类（成功 / 取消为 null）。
 */
data class QueryAuditRecord(
    val queryId: String,
    val connectionId: String,
    val sql: String,
    val startedAt: Long,
    val durationMs: Long,
    val status: QueryState,
    val rowsFetched: Long,
    val errorCode: OlapErrorCode?,
)
