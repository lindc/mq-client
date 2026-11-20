package com.mqclient.starrocks.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.olap.base.query.ResultColumn
import com.mqclient.uicommon.i18n.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 控制台的**阶段机**：连接与查询是互斥的两个阶段。
 *
 * 旧实现用 `connecting: Boolean` + `grid.streaming: Boolean` 两套状态，且连接
 * 成功后的整个查询期间 `connecting` 仍为 true（直到 launch 的 finally 才复位）
 * —— 结果区一直显示「正在建立连接...」，文案与事实不符。单一阶段机让文案
 * 与阶段一一对应，不可能组合出「已连接 + 显示建立连接」这种非法态。
 */
internal enum class ConsolePhase {
    /** 未执行过（初始提示）。 */
    Idle,

    /** 懒加载自动建连中 → 「正在建立连接...」。 */
    Connecting,

    /** 连接已就绪、查询进行中 → 「正在执行查询...」。 */
    Executing,

    /** 本轮结束：结果 / 错误由 [ConsoleGrid] 表达，不再显示加载态。 */
    Finished,
}

/**
 * 结果网格的可观察状态：**流式收集的落点**。
 *
 * `columns` / `rows` 用 Compose 快照 List —— 协程里每收一批就 append，
 * `LazyColumn` 随之增量重组（边收边渲染，不等全部收完）。
 */
internal class ConsoleGrid {
    val columns = mutableStateListOf<ResultColumn>()
    val rows = mutableStateListOf<List<Any?>>()

    /** 本轮是否已发起过执行（区分「初始」与「空结果集」）。 */
    var hasRun by mutableStateOf(false)

    /** 超过 [MAX_ROWS] 截断（防 OOM）：停止 append 并取消后续拉取。 */
    var truncated by mutableStateOf(false)

    /** 最近一条 DML / DDL 的受影响行数（SELECT 流为 null）。 */
    var updateCount by mutableStateOf<Long?>(null)

    /** 真实墙钟耗时（全部语句累计）。 */
    var durationMs by mutableLongStateOf(0L)

    /** 执行失败的用户文案（`userMessage()` 翻译后的）；非空 = 本轮以失败告终。 */
    var error by mutableStateOf<String?>(null)

    /** 本轮语句统计：成功 / 失败条数（多条执行的状态栏）。 */
    var successCount by mutableIntStateOf(0)
    var failedCount by mutableIntStateOf(0)

    fun reset() {
        columns.clear()
        rows.clear()
        hasRun = false
        truncated = false
        updateCount = null
        durationMs = 0L
        error = null
        successCount = 0
        failedCount = 0
    }
}

/** OOM 保护：超过该行数停止 append 并取消拉取（数据仍在服务端，客户端不再累积）。 */
internal const val MAX_ROWS = 50_000

/** 草稿防抖窗口（停止输入后毫秒数）。 */
internal const val DRAFT_DEBOUNCE_MS = 1_000L

/** 截断信号：继承 [CancellationException] 以结束当前收集协程，但不算错误。 */
internal class StopStream : CancellationException("result set truncated at $MAX_ROWS rows")
