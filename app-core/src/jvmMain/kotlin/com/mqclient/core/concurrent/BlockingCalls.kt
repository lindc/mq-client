package com.mqclient.core.concurrent

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 阻塞调用超时。[BlockingCalls.withDeadline] 在等待超过上限时抛它。
 *
 * 继承 [IllegalStateException]：调用点（连接 / 扫描）本来就是用「普通异常」表达失败，
 * 这样既有的 `catch (error: Throwable)` 收尾逻辑不用为它单开一条分支。
 */
class BlockingCallTimeoutException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * 给**阻塞**调用套一个真正有上限的等待。
 *
 * 为什么不能直接用 `withTimeout`：它只在挂起点生效。`MQQueueManager` 的构造、
 * `PCFMessageAgent.send` 都是纯阻塞、无挂起点的调用 —— 协程卡在里面时根本不检查取消，
 * 于是「30 秒超时」形同虚设，用户要等 TCP 层自己超时（可达十几分钟），期间界面停在
 * 「连接中」、连接的扫描锁也一直被占着（重连后点刷新会排在后面等）。
 *
 * 做法：把阻塞调用丢到一条独立线程上执行，调用方在**有限时间**内等它的结果。
 * 超时或取消时**放弃等待**（不是打断 —— 阻塞中的 socket 调用本来也不响应中断），
 * 但迟到的产出必须有人收拾，所以提供 [onAbandoned]：
 *
 * ```
 * BlockingCalls.withDeadline(timeoutMs = 30_000, label = "连接 QM1",
 *     onAbandoned = { late -> runCatching { late.disconnect() } }) {
 *     MQQueueManager("QM1", props)      // 纯阻塞
 * }
 * ```
 *
 * 语义边界（重要）：
 *  - 超时只保证**调用方**拿回控制权，不保证那条阻塞调用已经停止。它会在后台跑完，
 *    跑完后如果没有被放弃就返回结果、被放弃过就交给 [onAbandoned] 释放。
 *  - 因此超时后的「同一资源被两路使用」需要调用方自己判断是否可接受（例如扫描超时后，
 *    旧扫描仍在跑，新一轮可能并发访问同一个队列管理器）。
 *  - [onAbandoned] 恰好被调用一次（由完成回调或收尾协程中的一方触发，二者互斥）。
 *
 * 线程都是 daemon：应用退出时不会因为一条还没跑完的阻塞调用而卡住进程。
 */
object BlockingCalls {

    /** 等待切片：让取消最迟在这么久之后被察觉（单次 `future.get` 本身不响应取消）。 */
    private const val POLL_SLICE_MS = 100L

    /** 执行阻塞调用的线程池。每调用一条线程，跑完即回收。 */
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "blocking-call").apply { isDaemon = true }
    }

    /**
     * 收尾线程池：等被放弃的调用真正结束，再把它的产出交给 [onAbandoned]。
     *
     * 单独一个池而不是复用 [workers]：收尾任务本身是阻塞等待，混进同一个池会让缓存池
     * 无谓地多开线程（虽然也会回收，但会让「正在跑的阻塞调用」这个观测量失真）。
     */
    private val reapers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "blocking-call-reaper").apply { isDaemon = true }
    }

    /**
     * 在 [timeoutMs] 内等待 [block] 的结果。
     *
     * @param timeoutMs 等待上限（毫秒）。
     * @param label 超时文案里的主语，如「连接 QM1」「PCF 查询」。
     * @param onAbandoned 被放弃的调用**最终完成**时释放其产出；不关心就留空。
     *   若 [block] 抛异常则不会被调用。
     * @throws BlockingCallTimeoutException 超时。
     * @throws CancellationException 调用方被取消（原样上抛，保持取消语义）。
     * @throws Throwable [block] 自己抛的异常（解掉 `ExecutionException` 包装后原样上抛）。
     */
    suspend fun <T> withDeadline(
        timeoutMs: Long,
        label: String,
        onAbandoned: (T) -> Unit = {},
        block: () -> T,
    ): T {
        require(timeoutMs > 0) { "timeoutMs 必须为正数：$timeoutMs" }
        val future = workers.submit(Callable { block() })
        try {
            return awaitWithin(future, timeoutMs, label)
        } catch (timeout: BlockingCallTimeoutException) {
            // 放弃等待：迟到的产出交给收尾线程收拾。
            reap(future, onAbandoned)
            throw timeout
        } catch (cancellation: CancellationException) {
            // 被取消同样要收拾 —— 否则那条阻塞调用跑完后它的产出就没人关了。
            reap(future, onAbandoned)
            throw cancellation
        }
    }

    /**
     * 有上限地等 [future]；每 [POLL_SLICE_MS] 检查一次取消。
     *
     * 分段等待而不是一次 `get(timeoutMs)`：后者在协程被取消时不会返回（阻塞调用不响应
     * 取消），模块关闭时就要白等到超时上限。
     */
    private suspend fun <T> awaitWithin(future: Future<T>, timeoutMs: Long, label: String): T =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                coroutineContext.ensureActive()
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    throw BlockingCallTimeoutException("$label 超时（${timeoutMs / 1000}s）")
                }
                try {
                    return@withContext future.get(minOf(remaining, POLL_SLICE_MS), TimeUnit.MILLISECONDS)
                } catch (sliceTimeout: TimeoutException) {
                    // 这一小片没等到，继续 —— 总超时由上面的 deadline 判定。
                } catch (failed: ExecutionException) {
                    // 解掉 Future 的包装：调用方要看到 MQException 这些原始类型。
                    throw failed.cause ?: failed
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw BlockingCallTimeoutException("$label 被中断", interrupted)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("unreachable")
        }

    /** 等被放弃的调用结束并释放产出；本身失败不影响调用方。 */
    private fun <T> reap(future: Future<T>, onAbandoned: (T) -> Unit) {
        reapers.execute {
            runCatching {
                val late = future.get()
                onAbandoned(late)
            }
        }
    }
}
