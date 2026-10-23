package com.mqclient.core.concurrent

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] 的**可取消**版本：吞掉业务异常，但**原样上抛**取消。
 *
 * 为什么需要它：Kotlin 的 [runCatching] 捕获 `Throwable`，于是 `CancellationException`
 * 也被当成「失败」吞进 `Result`。在挂起函数里用它会静默破坏取消语义 —— 用户点了停止
 * / 模块正在关闭 / 扫描被新一轮顶掉，代码却当成「这次调用出错了」继续往下跑。
 *
 * 这不是假设：项目里已有三个文件在注释里写下了这条纪律
 * （`AiChatHistoryHandler` / `AiConnectionSnapshotHandler` / `AiChatPersistence`），
 * 而同一个错误仍在其它地方出现 —— 因为「写对」需要每次记得多写两行 catch。
 * 有了这个函数，正确写法就是最短的写法。
 *
 * 用法与 [runCatching] 完全一致（`inline` + 非 suspend lambda，所以挂起调用照样能写在
 * 里面）：
 *
 * ```kotlin
 * val body = runCatchingCancellable { response.bodyAsText() }.getOrNull()
 * val status = runCatchingCancellable { objectScanner.scanChannelStatuses(id) }
 *     .onFailure { log.warn("status query failed: ${it.message}") }
 *     .getOrDefault(emptyList())
 * ```
 *
 * 边界：只区分「取消」与「其它异常」两类。`Error`（`OutOfMemoryError` 等）仍走
 * `Result.failure` —— 与 [runCatching] 一致，保持可直接替换。
 */
inline fun <R> runCatchingCancellable(block: () -> R): Result<R> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
