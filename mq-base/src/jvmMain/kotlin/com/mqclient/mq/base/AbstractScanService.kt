package com.mqclient.mq.base

import com.mqclient.core.cache.CacheManager
import com.mqclient.core.cache.allKindsFailed
import com.mqclient.core.concurrent.KeyedJobRegistry
import com.mqclient.core.concurrent.KeyedMutex
import com.mqclient.core.log.Log
import com.mqclient.core.log.ModuleLogger
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/**
 * 扫描服务的**共同骨架**（Kafka / RabbitMQ / RocketMQ 三个 `*ScanService` 的基类）。
 *
 * ## 与 [AbstractConnectionManager] 的分工
 *
 * `AbstractConnectionManager` 管「连接怎么建、怎么断、什么时候算死」；
 * 本类管「扫描怎么串行、失败了怎么记账、缓存 TTL 怎么算」。两者都只收**逐字重复**
 * 的部分，扫描本体（扫哪些对象、并发几路、事件 payload）留给子类。
 *
 * ## 抽取的东西（此前三份副本）
 *
 * | 成员 | 重复情况 |
 * | --- | --- |
 * | `scanLocks` + [withScanLock] | 三份逐字相同（每连接一把锁，防重叠扫描互踩） |
 * | `manualScans` + [launchManualScan] + [cancelScan] | 三份逐字相同 |
 * | [objectsTtlMs] / [fastTtlMs] | 公式相同，只是取的配置字段名不同 |
 * | [cacheOf] | 三份逐字相同 |
 * | [markFailed] | 三份逐字相同（转给连接管理器按阈值判定） |
 * | [isConnectionBroken] | Kafka/Rocket 是成员、Rabbit 是顶层函数，语义相同 |
 * | [scanKindOr] | 三份实现相同，**名字**三种（`safely` / `scanTypeOr` / `scanKindOr`） |
 * | [errorMessage] | 默认实现相同；Kafka/Rocket 覆盖来解 `ExecutionException` |
 * | [reportAllKindsFailed] | 「清失败标记 + 发失败事件 + 记账」的三段式相同 |
 *
 * 名字不统一本身就是漂移的证据：同一个语义（「单类失败不拖垮整轮」）在三个模块里
 * 叫三个名字，读代码的人得先确认它们真的一样。
 *
 * ## 子类必须实现
 *
 * [contextOrNull] / [scanObjects] / [allKinds] / [describeKind] / 两个间隔与两个事件名，
 * 以及三个「模块级上报」钩子（模块对象上的 `reportScanFailure(s)` / `clearScanFailures`）。
 *
 * @param Ctx 本模块的连接上下文（必须带 `cache`，见 [AbstractConnectionContext]）。
 * @param Kind 本模块的扫描类型枚举（`KafkaScanKind` 等）。
 */
abstract class AbstractScanService<Ctx : AbstractConnectionContext<*>, Kind : Any>(
    protected val context: ServiceContext,
    protected val moduleId: String,
) {

    /** 模块日志句柄（tag = [moduleId]）。 */
    protected val log: ModuleLogger = Log.withTag(moduleId)

    /**
     * 按连接分组的扫描锁：同一条连接串行（防重叠扫描互相覆盖结果），不同连接并发。
     *
     * 一把全局锁会让 A 的慢扫挡住 B 的刷新 —— 粒度必须是连接。
     */
    private val scanLocks = KeyedMutex()

    /**
     * 手动刷新（UI 的「重新扫描」按钮）的协程：同一连接只留一条。
     *
     * 定时任务的协程归 `JobScheduler` 管，这里只收「用户点出来的那一次」。断开 / 删除
     * 连接时必须经 [cancelScan] 收掉 —— 否则它会拿着已经 `close()` 的句柄继续跑，
     * 而且一直占着 [scanLocks] 的 key，重连后的第一次刷新要排在它后面等
     * （表现是「点刷新没反应」）。
     */
    private val manualScans = KeyedJobRegistry()

    /* ------------------------------ 子类钩子 ------------------------------ */

    /** 该连接的上下文（未登记 / 已删除时 null）。各模块的 `*Module.connectionOrNull`。 */
    protected abstract fun contextOrNull(connectionId: String): Ctx?

    /**
     * 一轮对象慢扫。基类用它驱动 [launchManualScan]，并要求实现方用 [withScanLock]
     * 把整轮包起来（与定时任务共用同一把锁）。
     */
    abstract suspend fun scanObjects(connectionId: String)

    /** 本模块全部扫描类型（`SomeKind.entries`）—— [isConnectionBroken] 的判据。 */
    protected abstract val allKinds: Collection<Kind>

    /** 类型 → 日志里的短标签（`kind.label`）。 */
    protected abstract fun describeKind(kind: Kind): String

    /** 对象慢扫的配置间隔（毫秒）—— 各模块配置字段不同，所以由子类给原始值。 */
    protected abstract fun objectsScanIntervalMs(ctx: Ctx): Long

    /** 心跳快扫的配置间隔（毫秒）。 */
    protected abstract fun fastScanIntervalMs(ctx: Ctx): Long

    /** 整轮失败 → 交给连接管理器按连续失败阈值判定（`*Module.reportScanFailure`）。 */
    protected abstract fun reportScanFailure(connectionId: String, message: String): Boolean

    /** 本轮哪几类没扫到（`*Module.reportScanFailures`，供 UI 提示条读取）。 */
    protected abstract fun reportScanFailures(connectionId: String, kinds: Set<Kind>)

    /** 摘掉该连接的失败结论（`*Module.clearScanFailures`）。 */
    protected abstract fun clearScanFailures(connectionId: String)

    /** 整轮扫描失败的模块事件名（`*Events.OBJECTS_SCAN_FAILED`）。 */
    protected abstract val objectsScanFailedEvent: String

    /**
     * 异常 → 日志 / 事件里的失败原因。
     *
     * 默认取 message / 类名；Kafka / RocketMQ 覆盖它来解 `ExecutionException`
     * （它们的 future 路径会包一层，真正的错误藏在 cause 里）。
     */
    protected open fun errorMessage(error: Throwable): String =
        error.message ?: error::class.simpleName ?: "unknown"

    /* ------------------------------ 扫描串行 ------------------------------ */

    /**
     * 在**该连接的扫描锁**内执行 [block]：定时扫描与手动刷新共用同一把锁，
     * 永远不会有两轮扫描同时压在同一条连接上。
     */
    protected suspend fun <T> withScanLock(connectionId: String, block: suspend () -> T): T =
        scanLocks.withLock(connectionId, block)

    /**
     * 启动一次手动对象扫描：与定时慢扫共用 [withScanLock]，所以不会与正在跑的那一轮
     * 重叠；同一连接重复点击顶掉上一轮（与 JobScheduler 的「同 ID 重注册」同口径）。
     */
    fun launchManualScan(connectionId: String) {
        manualScans.launch(context.coroutineScope, connectionId) { scanObjects(connectionId) }
    }

    /**
     * 取消该连接上正在进行的手动扫描（断开 / 删除连接时由连接管理器调用）。
     * 只 cancel 不 join：阻塞中的协议调用不会被立刻打断，断开路径不能被一次慢查询拖住。
     */
    fun cancelScan(connectionId: String) {
        manualScans.cancel(connectionId)
    }

    /* ------------------------------ 缓存与 TTL ------------------------------ */

    /** 该连接的缓存（连接未登记 / 未连接时 null）。 */
    protected fun cacheOf(connectionId: String): CacheManager? = contextOrNull(connectionId)?.cache

    /**
     * 慢扫数据 TTL：配置的慢扫间隔 × [OBJECTS_TTL_FACTOR]（1.5）。
     *
     * 比扫描间隔长一截，只是「失联后数据别永远烂在缓存里」的兜底 —— 正常路径靠每轮
     * 扫描全量替换。
     */
    protected fun objectsTtlMs(ctx: Ctx): Long =
        (objectsScanIntervalMs(ctx) * OBJECTS_TTL_FACTOR).toLong()

    /** 快扫数据 TTL：配置的心跳间隔 × [FAST_TTL_FACTOR]（2）—— 容忍漏掉一拍。 */
    protected fun fastTtlMs(ctx: Ctx): Long =
        (fastScanIntervalMs(ctx) * FAST_TTL_FACTOR).toLong()

    /* ------------------------------ 失败记账 ------------------------------ */

    /**
     * 单类查询的兜底：失败 → [fallback] 且把该类登记进 [failures]（带错误文案）。
     *
     * 取消除外：取消不是「这个类失败了」，原样上抛让整轮退出。这条是本项目反复踩过的
     * 坑（`runCatching` 会把 `CancellationException` 一起吞掉，于是取消被当成
     * 「这一类扫空了」，剩下的请求还接着发）。
     *
     * WARN 而不是 ERROR：单类失败很可能是权限缺口（预期内，例如只读账号读不到
     * RabbitMQ 的 `/api/nodes`、Kafka 的某些 topic）。真正「整轮不可用」由调用方
     * 在全部失败时升为 ERROR。
     */
    suspend fun <T> scanKindOr(
        failures: MutableMap<Kind, String>,
        kind: Kind,
        fallback: T,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        val message = errorMessage(error)
        failures[kind] = message
        log.warn("objects scan kind skipped: kind=${describeKind(kind)} error=$message")
        fallback
    }

    /**
     * 全部类型都失败 —— 整体不可用，应把连接按连续失败阈值判死。
     *
     * 单类失败常常只是权限缺口，把整条连接标成 Failed 会让一个完全可用的只读账号
     * 显示成红色错误、点重连也没用。全部失败才是「一条请求都读不通」。
     */
    fun isConnectionBroken(failedKinds: Collection<Kind>): Boolean =
        allKindsFailed(failedKinds, allKinds)

    /**
     * 整轮扫描失败 → 交给连接管理器按「连续失败阈值」判定是否置 Failed。
     *
     * 旧写法是各模块直接写 `ctx.status = Failed`：单次失败就判死，而扫描入口本身又
     * 要求 `Connected` —— 判死之后心跳再也不跑，健康连接被永久误判为「未连接」。
     * 现在状态翻转集中在 `AbstractConnectionManager.onScanFailed`。
     */
    protected fun markFailed(connectionId: String, message: String) {
        reportScanFailure(connectionId, message)
    }

    /**
     * 「所有类型都失败」的统一收尾：清失败标记（整体不可用时「部分」已无意义）→
     * 发失败事件 → 记账。
     *
     * @return 恒为 true —— 便于调用方写 `if (reportAllKindsFailed(...)) return@withScanLock`。
     */
    protected fun reportAllKindsFailed(
        connectionId: String,
        failures: Map<Kind, String>,
        fallbackMessage: String = "全部请求均失败",
    ): Boolean {
        val message = failures.values.firstOrNull() ?: fallbackMessage
        log.error(
            "objects scan failed (all kinds): connection=$connectionId " +
                "errors=" + failures.entries.joinToString(", ") { "${describeKind(it.key)}=${it.value}" },
        )
        // 整体不可用时「部分」已无意义：界面走连接错误态，旧的残数据提示要让位。
        clearScanFailures(connectionId)
        context.emit(
            name = objectsScanFailedEvent,
            payload = mapOf("connectionId" to connectionId, "message" to message),
        )
        markFailed(connectionId, message)
        return true
    }

    companion object {
        /**
         * 慢扫数据 TTL 倍率：慢扫间隔 × 1.5（与 IBM MQ 的「TTL = 90min / 间隔 60min」
         * 同一口径）。
         */
        const val OBJECTS_TTL_FACTOR: Float = 1.5f

        /** 快扫数据 TTL 倍率：心跳间隔 × 2（容忍漏掉一拍）。 */
        const val FAST_TTL_FACTOR: Float = 2f
    }
}
