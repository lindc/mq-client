package com.mqclient.starrocks.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mqclient.core.CoreModule
import com.mqclient.core.event.EventNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 面板侧的查询结果类型：**别名**到 service 的唯一实现
 * （[com.mqclient.starrocks.query.QueryRows]），不另建一份结构。
 */
internal typealias StarRocksQueryRows = com.mqclient.starrocks.query.QueryRows

/**
 * 字节 → 人话：**委托给 ui-common 的 [com.mqclient.uicommon.table.formatSize]**。
 *
 * 本模块原有一份私有实现（到 PB、丢弃 `.0`），与消息表格那份（只到 MB）在同一个
 * 应用里给出两种风格（`529 KB` vs `529.3 KB`）。合并后全应用只有一种：
 * B 为整数，其余保留 1 位小数，单位自动上浮到 PB —— 集群容量（几百 GB）与
 * 消息大小（几 KB）用同一个函数都不会难读。
 */
internal fun formatBytes(bytes: Long): String = com.mqclient.uicommon.table.formatSize(bytes)

/**
 * 百分比展示：`0.53 %` → `0.53%`（小于 1% 保留 2 位，否则 1 位；≥10% 取整）。
 * 保留小数是因为 StarRocks 的 CPU 使用率常态就是个位数。
 */
internal fun formatPercent(percent: Double): String {
    // 非有限值（NaN / ±Infinity）不是"零使用率"：实测 kotlin.math.round 对 NaN
    // 返回 NaN（显示 "NaN%"），对 Infinity 返回 Long.MAX_VALUE（显示
    // "9223372036854775807%"）。两者都是无意义的天文数字/乱码，与项目其它
    // "无数据"的表示对齐，统一返回 "-"。
    if (!percent.isFinite()) return "-"
    val text = when {
        percent >= 10 -> kotlin.math.round(percent).toLong().toString()
        percent >= 1 -> (kotlin.math.round(percent * 10) / 10).toString()
        else -> (kotlin.math.round(percent * 100) / 100).toString()
    }
    return "$text%"
}

/**
 * 「存活」语义色：暗色主题用亮绿（Material 深绿在深底上对比度不足），浅色用深绿。
 *
 * Compose M3 没有内置 success 语义色。此前概览页内部有一份私有实现，系统页
 * （backends / ha）也要用 —— 于是提到这里做**唯一出处**，两处不可能漂移。
 */
@Composable
internal fun aliveSemanticColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32)

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
 * 绑定到 [connectionId] 的协程作用域：连接切换 / 面板离开时**取消其中所有任务**。
 *
 * 为什么不能用裸 `rememberCoroutineScope()`：它的生命周期跟着**组合位置**走，
 * 而宿主的 Tab 内容处在固定槽位（`MainWindow` 的 `tabContent()` 没有 key）。
 * 于是切换连接时：
 *  - 旧连接发起的取数（`ensureColumns` / 自动刷新 / 导出）继续在跑；
 *  - 它们走到 `ensureSession` 时会把**新连接刚建好的会话抢占掉**
 *    （`AbstractAnalyticsConnectionManager` 的单槽语义：`slot stolen` → close old session）——
 *    新连接正在跑的查询被凭空掐断，左侧树状态来回跳；
 *  - 控制台的「停止」按钮也停不掉它（`job` 已按连接分键，句柄丢了）。
 *
 * 这里返回的是子作用域：随 `connectionId` 重建，旧的在 `onDispose` 里被取消，
 * 于是上面三种症状一起消失。子作用域取消不影响组合自身的 scope。
 */
@Composable
internal fun rememberConnectionScope(connectionId: String): CoroutineScope {
    val parent = rememberCoroutineScope()
    val scoped = remember(connectionId) {
        CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))
    }
    DisposableEffect(connectionId) {
        onDispose { scoped.cancel() }
    }
    return scoped
}

/**
 * 可选分段取数：失败降级为 null，**取消照旧上抛**。
 *
 * 用来替掉 `runCatching { … }.getOrNull()` —— 后者会把 `CancellationException`
 * 一起吞掉，于是「切换连接」被当成「这一段查询失败」，后面的 SQL 还会继续发出去。
 */
internal inline fun <T> runOptional(block: () -> T): T? = try {
    block()
} catch (c: CancellationException) {
    throw c
} catch (error: Throwable) {
    if (error is Error) throw error
    null
}

/**
 * 面板取数的**唯一错误出口**：取消必须上抛，其余异常交给 [onError]。
 *
 * 为什么要有这一层：`LaunchedEffect(connectionId)` 在切换连接 / 离开页面时会取消
 * 旧协程，抛出 `CancellationException`。裸 `catch (e: Throwable)` 会把它当成业务
 * 失败写进 `error`，于是 `userMessage()` 把 `Job was cancelled` 这类内部英文直接
 * 弹到界面上 —— 而这个错误还会盖住紧接着发起的新请求的结果。
 *
 * 用 `catch (c: CancellationException) { throw c }` 写在每个面板里也能修，但那样
 * 每新增一个面板就多一次「忘了写」的机会（模块里已经出现过 4 次）。收成一个函数后，
 * 取数路径只有一条，取消语义由机制保证，不依赖记性。
 *
 * @return 成功时返回 block 的结果；失败时返回 null（调用方据此进入错误态）。
 */
internal suspend fun <T> runPanelLoad(
    onError: (Throwable) -> Unit,
    block: suspend () -> T,
): T? = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: Throwable) {
    // Error（OOM 等）同样不该被当成「业务失败」吞掉：面板显示一个红字救不回堆。
    if (error is Error) throw error
    onError(error)
    null
}

/**
 * 面板取数：委托给 service 的**唯一实现**
 * （[com.mqclient.starrocks.query.queryRows]）—— 连接守卫、日志、打点、取消语义
 * 全在那一处，这里只固定 `origin = "panel"` 便于按来源过滤日志。
 *
 * 异常原样上抛，由面板经 [runPanelLoad] 转成错误态。
 */
internal suspend fun queryRows(connectionId: String, sql: String): StarRocksQueryRows =
    com.mqclient.starrocks.query.queryRows(connectionId, sql, origin = "panel")
