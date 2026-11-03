package com.mqclient.mqui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionFormState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 「保存连接」流程的公共状态机（四个 MQ 模块共用的表单基类）。
 *
 * ## 为什么抽出来
 *
 * IBM MQ / Kafka / RabbitMQ / RocketMQ 的 `*ConnectionFormState` 里，「完成」这条链路
 * 是逐字重复的：校验 → 按草稿配置发起临时连接测试（15s 超时）→ 成功落盘并回调宿主
 * 关窗 / 失败弹「是否仍要保存」→ 用户选「仍要保存」按快照落盘。四份副本带来的是
 * 定向漂移（例如某一份忘了在落盘失败时清 `pendingConfig`、某一份把超时当成了普通
 * 失败），而且每次调整交互（比如加一个「保存中」态）都要改四遍。
 *
 * ## 模板
 *
 * ```
 * submit()  ─ 校验失败 → 分字段错误（子类负责展示）
 *           └ 通过 → canTestConnection? ─ 否 → 直接落盘（无头环境）
 *                                     └ 是 → Testing → testConnection(config)
 *                                              ├ 成功 → persist(config) → 成功回调 / 失败提示
 *                                              └ 失败 → Failed(msg) → 弹「是否仍要保存」
 * confirmSave() → 按 pendingConfig 落盘
 * dismissFailed() → 关确认框，回到 Idle（对话框保留，可继续改）
 * ```
 *
 * ## 子类只需实现
 *
 * [validate] / [buildConfig] / [testConnection] / [testTimeoutMessage] / [persist]，
 * 以及可选的 [canTestConnection]（IBM MQ 在模块未初始化时跳过测试）与
 * [saveFailureMessage]（Kafka 用本地化文案，其余用原始 message）。
 *
 * @param C 各模块的配置类型（`KafkaConfig` / `RabbitMqConfig` …）。
 */
abstract class AbstractConnectionFormState<C : Any>(
    /** 表单自己的协程作用域（宿主传入，随面板销毁）。 */
    protected val formScope: CoroutineScope,
    /** 「保存前测试连接」的超时（毫秒）；默认与四个模块一致的 15 秒。 */
    private val testTimeoutMs: Long = DEFAULT_TEST_TIMEOUT_MS,
) : ConnectionFormState {

    /** 宿主回填的「已保存」回调（见 [ConnectionFormState.onSaved] 的约定）。 */
    private var savedCallback: (() -> Unit)? = null

    override var onSaved: (() -> Unit)?
        get() = savedCallback
        set(value) {
            savedCallback = value
        }

    /* ------------------------------ 流程状态 ------------------------------ */

    /** 保存链路状态（Idle / Testing / Failed）；面板据此渲染「测试中」与确认框。 */
    var saveState: SaveState by mutableStateOf(SaveState.Idle)
        protected set

    /** 落盘失败的原因（面板在表单底部展示）。 */
    var saveError: String? by mutableStateOf(null)
        protected set

    /** 测试失败待确认的配置快照：用户选「仍要保存」时按它落盘。 */
    var pendingConfig: C? by mutableStateOf(null)
        private set

    /** 编辑模式的目标连接 id；null = 新建。 */
    var editingId: String? by mutableStateOf(null)
        protected set

    /**
     * 落盘进行中（「仍要保存」路径）：落盘已挪到 IO 线程，这段窗口里必须挡住重复提交，
     * 否则双击会写出两条连接（[com.mqclient.core.ConnectionStore.update] 每次都换发新 id，
     * 不会报「已存在」）。
     */
    private var saving by mutableStateOf(false)

    override val busy: Boolean get() = saveState == SaveState.Testing || saving

    /* ------------------------------ 子类钩子 ------------------------------ */

    /** 校验草稿；失败时应把分字段错误写进子类自己的 errors 状态并返回 false。 */
    protected abstract fun validate(): Boolean

    /** 由草稿构建配置对象（校验已通过）。 */
    protected abstract fun buildConfig(): C

    /**
     * 能否发起「保存前测试连接」。默认可以；IBM MQ 在模块未初始化（无头 / 单测环境）
     * 时返回 false —— 那时跳过测试直接落盘，配置对不对由保存后的扫描链路兜底上报。
     */
    protected open fun canTestConnection(): Boolean = true

    /** 用给定配置发起一次**临时**连接测试；失败抛异常。 */
    protected abstract suspend fun testConnection(config: C)

    /** 测试超时的提示文案（各模块自己的 i18n 键）。 */
    protected abstract fun testTimeoutMessage(): String

    /** 测试失败提示的前缀（各模块自己的 i18n 键），后面接 [describeFailure]。 */
    protected abstract fun testFailurePrefix(): String

    /** 落盘；返回成功 / 失败（失败原因由 [saveFailureMessage] 翻译）。 */
    protected abstract suspend fun persist(config: C): Result<Unit>

    /**
     * 落盘失败的原因文案。默认取异常的 message / 类名；Kafka 覆盖成
     * `kafkaErrorText`（解包 future 包装 + 本地化分类）。
     */
    protected open fun saveFailureMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "unknown"

    /**
     * 测试失败的原因文案：默认沿 cause 链取前几条去重拼接 —— MQException 之类顶层
     * 消息只有一行「MQJE001…Reason xxxx」，真正的细节（本地映射失败 / 服务端拒绝）
     * 在 cause 链里，确认框里要能看出到底是谁在拒绝。
     */
    protected open fun describeFailure(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(4)
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("  ←  ")
            .ifBlank { error::class.simpleName ?: "unknown" }

    /* ------------------------------ 宿主入口 ------------------------------ */

    /**
     * 保存链路入口（宿主「完成」→ 本方法）。全程返回 false：测试是慢操作，无法同步
     * 给出最终结果；宿主对 false 的唯一反应就是保持对话框打开。
     */
    override fun submit(): Boolean {
        if (saveState != SaveState.Idle) return false
        saveError = null
        if (!validate()) return false

        val config = buildConfig()
        if (!canTestConnection()) {
            // 没有可用的测试通道：直接落盘（见 canTestConnection 的说明）。
            startSave(config)
            return false
        }
        saveState = SaveState.Testing
        formScope.launch {
            try {
                withTimeout(testTimeoutMs) { testConnection(config) }
                runSave(config)
            } catch (e: TimeoutCancellationException) {
                // 超时不算「正常取消」，按失败处理（确认框可仍要保存）。
                fail(config, testTimeoutMessage())
            } catch (e: CancellationException) {
                // 对话框关闭 / 面板被销毁：正常退出，不落盘、不提示。
                throw e
            } catch (e: Exception) {
                fail(config, testFailurePrefix() + describeFailure(e))
            }
        }
        return false
    }

    /** 用户在确认框选「仍要保存」：按测试时的配置快照落盘。 */
    fun confirmSave() {
        val config = pendingConfig ?: return
        // 先摘掉待保存配置并关掉确认框（它只在 Failed 时渲染）：落盘是异步的，
        // 不清的话这段窗口里还能再点一次「保存」，写出两条连接。
        pendingConfig = null
        saveState = SaveState.Idle
        startSave(config)
    }

    /**
     * 复位保存链路（`reset` / 编辑模式 `loadFrom` 里调用）：清掉待保存快照、失败提示
     * 与状态机。子类自己的字段草稿复位不归这里管。
     */
    protected fun resetSaveFlow() {
        pendingConfig = null
        saveState = SaveState.Idle
        saveError = null
    }

    /** 用户在确认框选「取消」：只关确认框，回到 Idle —— 主对话框保留，表单原样可改。 */
    fun dismissFailed() {
        pendingConfig = null
        saveState = SaveState.Idle
    }

    /* ------------------------------ 内部 ------------------------------ */

    private fun fail(config: C, message: String) {
        pendingConfig = config
        saveState = SaveState.Failed(message)
    }

    /** 「仍要保存」路径：置 [saving] 挡住重复点击，落盘完再放开。 */
    private fun startSave(config: C) {
        saving = true
        formScope.launch {
            try {
                runSave(config)
            } finally {
                saving = false
            }
        }
    }

    /**
     * 落盘 + 收尾：成功回调宿主（关窗 + 重载），失败把原因写进 [saveError]。
     * 两条路径都必须把 [pendingConfig] 与 [saveState] 复位 —— 否则确认框会诈尸。
     */
    private suspend fun runSave(config: C) {
        val result = persist(config)
        pendingConfig = null
        saveState = SaveState.Idle
        result
            .onSuccess { savedCallback?.invoke() }
            .onFailure { saveError = saveFailureMessage(it) }
    }

    private companion object {
        /** 「保存前测试连接」的默认超时：与四个模块此前的硬编码一致。 */
        const val DEFAULT_TEST_TIMEOUT_MS = 15_000L
    }
}
