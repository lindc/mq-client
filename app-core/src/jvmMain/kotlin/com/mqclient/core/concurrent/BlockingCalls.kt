package com.mqclient.core.concurrent

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
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

    /**
     * 阻塞调用线程池上限。
     *
     * 原先用 `newCachedThreadPool`（理论上无上限）：每条线程都在等一个阻塞 socket
     * 调用，若 UI 侧反复触发（重试循环 / 多个对话框同时拉取），线程数会随「卡住的
     * 调用数」线性上涨。桌面应用里这个数字涨到几十就该被人注意到了，涨到几百则 GC
     * 与调度一起崩。32 已经足够（每个连接同时最多一两个阻塞调用）。
     */
    private const val MAX_WORKERS = 32

    /** 排队上限：超过就直接拒绝，而不是无界队列把内存吃满。 */
    private const val MAX_PENDING = 256

    /**
     * 收尾线程上限。收尾任务只做一件阻塞的事：等被放弃的调用结束。
     * 它比 workers 小得多 —— 一次「超时放弃」才产生一个收尾任务。
     */
    private const val MAX_REAPERS = 8

    /**
     * 收尾等待上限。
     *
     * 原先是裸的 `future.get()`（无超时）：一条收尾线程会被一条永远不返回的阻塞调用
     * 永久占着。8 个这样的死调用就能把收尾池彻底堵死，之后所有被放弃的产出都无人
     * 释放 —— 那本身就是泄漏。120s 远大于任何合理的 MQ 调用，超时说明那条调用已经烂在
     * socket 里，只能靠连接层兜底。
     */
    private const val REAP_TIMEOUT_MS = 120_000L

    /** 线程保活时间：空闲线程 60s 后回收（有界池 + 保活 = 不用时真正缩回）。 */
    private const val KEEP_ALIVE_SECONDS = 60L

    private val threadFactory: (Runnable) -> Thread = { runnable ->
        Thread(runnable, "blocking-call").apply { isDaemon = true }
    }

    /**
     * 执行阻塞调用的有界线程池。
     *
     * 核心 4 / 上限 32 + 有界队列 256：正常使用（连一两条 MQ、偶尔扫描）永远碰不到
     * 上限；真的撞上了，那说明有调用在集体卡死，此时**快速失败比默默堆线程更诚实**。
     */
    private val workers = ThreadPoolExecutor(
        4,
        MAX_WORKERS,
        KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(MAX_PENDING),
        threadFactory,
    )

    /**
     * 收尾线程池：等被放弃的调用真正结束，再把它的产出交给 `onAbandoned`。
     *
     * 单独一个池而不是复用 [workers]：收尾任务本身是阻塞等待，混进同一个池会让
     * 「正在跑的阻塞调用」这个观测量失真（也会无谓占用 worker 名额）。
     */
    private val reapers = ThreadPoolExecutor(
        1,
        MAX_REAPERS,
        KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(MAX_PENDING),
    ) { runnable -> Thread(runnable, "blocking-call-reaper").apply { isDaemon = true } }

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
        val future = try {
            workers.submit(Callable { block() })
        } catch (rejected: RejectedExecutionException) {
            // 有界池饱和是**可预期的**上限，不是 bug：转成同一套超时异常，调用方的
            // 既有 catch（扫描 / 连接路径都在 catch BlockingCallTimeoutException）无需改。
            throw BlockingCallTimeoutException(
                "$label 未执行：阻塞调用线程池已满（并发上限 $MAX_WORKERS，排队上限 $MAX_PENDING）",
                rejected,
            )
        }
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

    /**
     * 等被放弃的调用结束并释放产出；本身失败不影响调用方。
     *
     * 必须是**有上限**的等待：一条永远不返回的阻塞调用会永久占住一条收尾线程，
     * 把 [reapers] 池堵死。超时就放弃 —— 拿不到产出，也就无从释放，只能靠连接层
     * 在断开时兜底（这也是为什么各模块的 closeQuietly 都要自己关句柄）。
     */
    private fun <T> reap(future: Future<T>, onAbandoned: (T) -> Unit) {
        runCatching {
            reapers.execute {
                try {
                    val late = future.get(REAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    onAbandoned(late)
                } catch (_: TimeoutException) {
                    // 调用已经烂在 socket 里：不再等、不调 onAbandoned（没有产出）。
                } catch (_: Throwable) {
                    // block 自己失败 / 被取消：没有产出可释放。
                }
            }
        }.onFailure {
            // 收尾池也饱和：只能放弃。不能因为清理失败而打断调用方。
        }
    }

    /**
     * 丢弃线程池。由应用退出链（`shutdownApp`）调用。
     *
     * `shutdownNow()` 而不是 `shutdown()`：池里跑的是**不响应中断**的阻塞调用，
     * 优雅等待等于等它们自己超时（可能十几分钟）。线程都是 daemon，直接丢弃不会
     * 阻止 JVM 退出；未执行的排队任务也就一并丢弃了 —— 退出路径上没人要它们的结果。
     */
    fun shutdown() {
        workers.shutdownNow()
        reapers.shutdownNow()
    }
}
