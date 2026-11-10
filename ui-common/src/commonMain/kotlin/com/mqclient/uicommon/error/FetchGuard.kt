package com.mqclient.uicommon.error

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 拉取类协程的统一错误分类（四个 MQ 浏览面板共用的唯一实现）。
 *
 * ## 为什么需要它
 *
 * service 层的「整体超时」（`withTimeout` 到点）抛的 `TimeoutCancellationException`
 * 是 `CancellationException` 的**子类**。面板里手写 `catch (e: CancellationException) {
 * throw e }` 会把它当成本协程的取消直接 rethrow —— effect 静默结束，`uiState` 永远
 * 停在 Loading（真机踩过：RocketMQ 15s 超时后无限转圈）。
 *
 * ## 分类规则
 *
 *  - 本协程**真被取消**（关对话框 / 切页 / 点重试）→ [runGuardedFetch] 在此 rethrow，
 *    走正常取消路径，调用方代码不会执行；
 *  - **幸存的 CancellationException** 只能来自 service 层的 `withTimeout`（或被协议
 *    客户端包装后重新抛出的取消）→ [FetchOutcome.Timeout]；
 *  - 其余异常 → [FetchOutcome.Failure]（协议 / 网络 / 权限等业务失败）。
 */
sealed interface FetchOutcome<out T> {

    /** 拉取成功，携带结果。 */
    data class Success<T>(val value: T) : FetchOutcome<T>

    /** service 层整体超时到点（或被包装的取消信号）；不是本协程的取消。 */
    data class Timeout(val cause: CancellationException) : FetchOutcome<Nothing>

    /** 业务异常（协议 / 网络 / 权限…）；文案用 `cause.userMessage()` 取。 */
    data class Failure(val cause: Throwable) : FetchOutcome<Nothing>
}

/**
 * 执行一个挂起拉取块并把「取消 / 超时 / 失败」三类出口分类，见 [FetchOutcome]。
 *
 * 调用方在 `when (outcome)` 里各自处理三态；成功路径的 `[MQ-Perf]` 耗时日志与
 * 失败路径的 `异常类型/详情` 日志仍由调用方打（带各自的协议 tag）。
 */
suspend fun <T> runGuardedFetch(block: suspend () -> T): FetchOutcome<T> = try {
    FetchOutcome.Success(block())
} catch (e: CancellationException) {
    // 区分「本协程被取消」与「service 层超时」：ensureActive 在协程已取消时抛出
    // （正常取消路径）；否则这个 CancellationException 是 service 层的取消型信号。
    currentCoroutineContext().ensureActive()
    FetchOutcome.Timeout(e)
} catch (e: Exception) {
    FetchOutcome.Failure(e)
}
