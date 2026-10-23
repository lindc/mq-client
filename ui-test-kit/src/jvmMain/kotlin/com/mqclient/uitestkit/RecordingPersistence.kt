package com.mqclient.uitestkit

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionPersistence
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel

/**
 * 一次落盘调用的观察记录：[thread] 是**实际执行写盘的那个线程**。
 */
data class RecordedPersist(
    val operation: Operation,
    val connection: ConnectionInfo,
    val thread: Thread,
) {
    enum class Operation { CREATE, UPDATE }
}

/**
 * [ConnectionPersistence] 的记录替身：只记调用（含线程），不碰磁盘。
 *
 * 用途是断言「写盘没有压在 UI 线程上」：把它注入表单状态，等一次落盘发生后检查
 * [RecordedPersist.thread] 与 [UiThreadHarness] 的 UI 线程不是同一个。
 *
 * 不写真实 `~/.mqclient/config.toml` 是刻意的 —— 测试不该因为本机的连接列表而改变行为，
 * 也不该往用户的配置里塞测试数据。
 */
class RecordingConnectionPersistence(
    /** 模拟落盘失败（用于验证失败路径）；默认成功。 */
    private val failWith: Throwable? = null,
) : ConnectionPersistence {

    private val recorded = CopyOnWriteArrayList<RecordedPersist>()
    private val firstCall = CountDownLatch(1)

    val calls: List<RecordedPersist> get() = recorded.toList()

    /** 已记录到的第一条调用；还没发生则为 null。 */
    val first: RecordedPersist? get() = recorded.firstOrNull()

    override fun create(connection: ConnectionInfo): Result<Unit> =
        record(RecordedPersist.Operation.CREATE, connection)

    override fun update(connection: ConnectionInfo): Result<Unit> =
        record(RecordedPersist.Operation.UPDATE, connection)

    /**
     * 等第一次落盘发生并返回它；超时（默认 5 秒）返回 null。
     *
     * 用 latch 而不是轮询列表：落盘在另一个线程（IO 池）上发生，测试线程必须有一个
     * 明确的等待点，否则会读到「还没写」的中间态而误判。
     */
    fun awaitFirst(timeoutMillis: Long = 5_000): RecordedPersist? {
        if (!firstCall.await(timeoutMillis, TimeUnit.MILLISECONDS)) return null
        return recorded.firstOrNull()
    }

    private fun record(operation: RecordedPersist.Operation, connection: ConnectionInfo): Result<Unit> {
        recorded += RecordedPersist(operation, connection, Thread.currentThread())
        firstCall.countDown()
        return failWith?.let { Result.failure(it) } ?: Result.success(Unit)
    }
}

/**
 * 一个「像 UI 线程」的调度器：单线程、有固定名字，供表单状态当作用域跑协程。
 *
 * 测试据此断言「落盘线程 != 这个线程」——也就是断言写盘没有压在界面线程上。
 * 用单线程而不是 `Dispatchers.Main`：桌面 Compose 的 Main 在测试环境里不存在，
 * 而单线程调度器与被替代的 UI 线程在语义上是同一件事（同一时刻只跑一个任务、
 * 协程恢复都在这一条线程上）。
 */
class UiThreadHarness(name: String = "ui-test") : AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    /** 表单状态用的「UI 调度器」。 */
    val dispatcher = executor.asCoroutineDispatcher()

    /** 表单状态用的作用域（单线程 + SupervisorJob，与 UI 侧的形态一致）。 */
    val scope = CoroutineScope(dispatcher + SupervisorJob())

    /** UI 线程本身（首次访问时创建）。 */
    val thread: Thread
        get() = executor.submit(Callable { Thread.currentThread() }).get(5, TimeUnit.SECONDS)

    /** 在「UI 线程」上执行 [block]（模拟点击回调发生在界面线程）。 */
    fun <T> onUi(block: () -> T): T = executor.submit(Callable { block() }).get(10, TimeUnit.SECONDS)

    /**
     * 等 [scope] 上已排队的协程跑完「当前这一段」。
     *
     * 往同一个单线程执行器投一个空任务并等它完成 —— 排在它前面的调度任务都已经执行过。
     * 注意它只保证**已排队**的任务跑完；协程如果又开始等 IO，需要另用 latch 等结果。
     */
    fun drain() {
        executor.submit(Runnable { }).get(5, TimeUnit.SECONDS)
    }

    /** 反复 [drain] 直到 [condition] 成立（用于等状态机从 Testing 走到 Failed 等转移）。 */
    fun awaitCondition(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            drain()
            Thread.sleep(10)
        }
        return condition()
    }

    override fun close() {
        scope.cancel()
        executor.shutdownNow()
    }
}

/**
 * 在 [harness] 的 UI 线程上跑 [block]，等它（含其后排队的一段）走完再返回。
 *
 * 只是 `onUi { }` + `drain()` 的组合，抽出来是为了让测试里「模拟一次点击」只有一行。
 */
fun <T> UiThreadHarness.runOnUi(block: () -> T): T {
    val result = onUi(block)
    drain()
    return result
}

/** 断言 [recorded] 的写盘线程与 [harness] 的 UI 线程不是同一条（即：没有压在 UI 线程上）。 */
fun assertOffUiThread(harness: UiThreadHarness, recorded: RecordedPersist) {
    val uiThread = harness.thread
    check(recorded.thread !== uiThread) {
        "落盘发生在 UI 线程 '${uiThread.name}' 上 —— 写 config.toml 会卡界面"
    }
}
