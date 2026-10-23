package com.mqclient.rabbitmq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionFormState
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionPersistence
import com.mqclient.core.ConnectionStore
import com.mqclient.core.newConnectionId
import com.mqclient.mqui.state.SaveState
import com.mqclient.rabbitmq.RabbitMqConfig
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.rabbitmq.rabbitMqConfigFromOptions
import com.mqclient.rabbitmq.toOptions
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 各字段自己的校验错误（null = 没问题）。分字段而不是一条总错误行：
 * 错误就显示在它所属字段的下方（红色小字）。
 */
data class RabbitMqFieldErrors(
    val host: String? = null,
    val amqpPort: String? = null,
    val managementPort: String? = null,
    val vhost: String? = null,
    val connectionTimeout: String? = null,
    val objectScanInterval: String? = null,
    val statusScanInterval: String? = null,
) {
    val hasAny: Boolean
        get() = host != null ||
            amqpPort != null ||
            managementPort != null ||
            vhost != null ||
            connectionTimeout != null ||
            objectScanInterval != null ||
            statusScanInterval != null
}

/**
 * RabbitMQ 连接表单的状态：字段草稿、分字段校验、保存链路（[SaveState] 状态机，与
 * Kafka / IBM MQ 表单同一套）、编辑模式载入。UI 直接读写这些状态，业务规则（校验
 * 范围、密码保留语义、落盘路径）全部收在本类里。
 *
 * 保存链路与 Kafka 完全一致：**同步表单改异步** —— 「完成」先按草稿配置发起一次
 * 临时连接测试（管理 API overview，15 秒超时；busy 经宿主契约禁用「完成」），成功
 * 直接落盘并回调宿主关窗；失败弹「是否仍要保存」确认框（[SaveAnywayDialog]）。
 *
 * 密码保留语义与 Kafka 一致：编辑模式载入后输入框留空、显示占位符，用户不动就
 * 沿用原值（原值留在 originalPassword，构建配置时取回）。
 */
class RabbitMqConnectionFormState(
    defaultName: String,
    private val scope: CoroutineScope,
    /** 落盘端口：默认直连 ConnectionStore，测试注入记录线程的替身。 */
    private val persistence: ConnectionPersistence = ConnectionPersistence.Default,
) : ConnectionFormState {

    companion object {
        /** 「保存前测试连接」的超时：与 Kafka 表单同款，15 秒不返回按超时处理。 */
        const val TEST_TIMEOUT_MS = 15_000L

        /** 端口的合法范围（AMQP 与管理端口同规则）。 */
        const val PORT_MIN = 1
        const val PORT_MAX = 65_535

        const val TIMEOUT_MIN = 5
        const val TIMEOUT_MAX = 300

        /** 对象慢扫间隔的合法范围（分钟）。 */
        const val OBJECT_SCAN_MIN = 1
        const val OBJECT_SCAN_MAX = 1440

        /** 状态快扫间隔的合法范围（秒）。 */
        const val STATUS_SCAN_MIN = 5
        const val STATUS_SCAN_MAX = 3600
    }

    /** ---------------- 宿主契约（busy / onSaved）---------------- */

    override val busy: Boolean get() = saveState == SaveState.Testing || saving

    /** ---------------- 流程状态 ---------------- */

    var defaultName by mutableStateOf(defaultName); private set
    var errors by mutableStateOf(RabbitMqFieldErrors()); private set
    var saveError by mutableStateOf<String?>(null); private set
    var saveState by mutableStateOf<SaveState>(SaveState.Idle); private set

    /**
     * 落盘进行中（「仍要保存」路径）：落盘已挪到 IO 线程，这段窗口里必须挡住重复提交，
     * 否则双击会写出两条连接（[ConnectionStore.update] 每次都换发新 id，不会报「已存在」）。
     */
    private var saving by mutableStateOf(false)

    /** 测试失败待确认的配置快照：用户选「仍要保存」时按它落盘。 */
    var pendingConfig by mutableStateOf<RabbitMqConfig?>(null); private set

    /** 编辑模式的目标连接 id；null = 新建。 */
    var editingId by mutableStateOf<String?>(null); private set

    /** ---------------- 字段草稿（数字存原字符串，构建时解析）---------------- */

    var name by mutableStateOf(defaultName)
    var host by mutableStateOf("")
    var amqpPort by mutableStateOf(RabbitMqConfig.DEFAULT_AMQP_PORT.toString())
    var managementPort by mutableStateOf(RabbitMqConfig.DEFAULT_MANAGEMENT_PORT.toString())
    var vhost by mutableStateOf(RabbitMqConfig.DEFAULT_VHOST)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var useTls by mutableStateOf(false)
    var tlsTruststorePath by mutableStateOf("")
    var tlsTruststorePassword by mutableStateOf("")
    var connectionTimeout by mutableStateOf(RabbitMqConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS.toString())
    var objectScanIntervalMinutes by mutableStateOf(RabbitMqConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES.toString())
    var statusScanIntervalSeconds by mutableStateOf(RabbitMqConfig.DEFAULT_STATUS_SCAN_INTERVAL_SECONDS.toString())

    // 编辑模式的原密码与「用户动过没有」标记：没动过 → 构建时沿用原值，
    // 界面上只显示占位符（见 loadFrom）。
    var originalPassword by mutableStateOf(""); private set
    var passwordChanged by mutableStateOf(false)

    /** ---------------- 公共动作 ---------------- */

    /** 分字段校验：错误写进 [errors]（字段下方红字），全部通过返回 true。 */
    fun validate(): Boolean {
        var e = RabbitMqFieldErrors()
        if (host.isBlank()) {
            e = e.copy(host = stringResource("rabbitmq_ui_error_host_required"))
        }
        val a = amqpPort.trim().toIntOrNull()
        if (a == null || a !in PORT_MIN..PORT_MAX) {
            e = e.copy(amqpPort = stringResource("rabbitmq_ui_error_port_range"))
        }
        val m = managementPort.trim().toIntOrNull()
        if (m == null || m !in PORT_MIN..PORT_MAX) {
            e = e.copy(managementPort = stringResource("rabbitmq_ui_error_port_range"))
        }
        if (vhost.isBlank()) {
            e = e.copy(vhost = stringResource("rabbitmq_ui_error_vhost_required"))
        }
        val t = connectionTimeout.trim().toIntOrNull()
        if (t == null || t !in TIMEOUT_MIN..TIMEOUT_MAX) {
            e = e.copy(connectionTimeout = stringResource("rabbitmq_ui_error_timeout_range"))
        }
        val o = objectScanIntervalMinutes.trim().toIntOrNull()
        if (o == null || o !in OBJECT_SCAN_MIN..OBJECT_SCAN_MAX) {
            e = e.copy(objectScanInterval = stringResource("rabbitmq_ui_error_object_scan_range"))
        }
        val s = statusScanIntervalSeconds.trim().toIntOrNull()
        if (s == null || s !in STATUS_SCAN_MIN..STATUS_SCAN_MAX) {
            e = e.copy(statusScanInterval = stringResource("rabbitmq_ui_error_status_scan_range"))
        }
        errors = e
        return !e.hasAny
    }

    /** 组装强类型配置：数字字段解析失败回落默认值（边界已由 [validate] 拦住）。 */
    fun buildConfig(): RabbitMqConfig = RabbitMqConfig(
        host = host.trim(),
        amqpPort = amqpPort.trim().toIntOrNull() ?: RabbitMqConfig.DEFAULT_AMQP_PORT,
        managementPort = managementPort.trim().toIntOrNull() ?: RabbitMqConfig.DEFAULT_MANAGEMENT_PORT,
        vhost = vhost.ifBlank { RabbitMqConfig.DEFAULT_VHOST },
        username = username.trim(),
        password = if (passwordChanged || originalPassword.isEmpty()) password else originalPassword,
        useTls = useTls,
        tlsTruststorePath = tlsTruststorePath.trim(),
        tlsTruststorePassword = tlsTruststorePassword,
        connectionTimeout = connectionTimeout.trim().toIntOrNull()
            ?: RabbitMqConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS,
        objectScanIntervalMinutes = objectScanIntervalMinutes.trim().toIntOrNull()
            ?: RabbitMqConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES,
        statusScanIntervalSeconds = statusScanIntervalSeconds.trim().toIntOrNull()
            ?: RabbitMqConfig.DEFAULT_STATUS_SCAN_INTERVAL_SECONDS,
    )

    /** 清空草稿回新建模式（宿主每次进入创建页都会调）。 */
    override fun reset(defaultName: String) {
        this.defaultName = defaultName
        editingId = null
        host = ""
        amqpPort = RabbitMqConfig.DEFAULT_AMQP_PORT.toString()
        managementPort = RabbitMqConfig.DEFAULT_MANAGEMENT_PORT.toString()
        vhost = RabbitMqConfig.DEFAULT_VHOST
        username = ""
        password = ""
        useTls = false
        tlsTruststorePath = ""
        tlsTruststorePassword = ""
        connectionTimeout = RabbitMqConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS.toString()
        objectScanIntervalMinutes = RabbitMqConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES.toString()
        statusScanIntervalSeconds = RabbitMqConfig.DEFAULT_STATUS_SCAN_INTERVAL_SECONDS.toString()
        originalPassword = ""
        passwordChanged = false
        errors = RabbitMqFieldErrors()
        saveError = null
        pendingConfig = null
        saveState = SaveState.Idle
    }

    /**
     * 编辑模式：从已有连接载入草稿。明文配置经 [rabbitMqConfigFromOptions] 还原
     * （options 读回时已由序列化器解密）；密码不回填明文 —— 输入框留空、显示
     * 占位符，用户不动就沿用原值。
     */
    override fun loadFrom(connection: ConnectionInfo) {
        val config = rabbitMqConfigFromOptions(connection.options)
        editingId = connection.id
        defaultName = connection.name
        name = connection.name
        host = config.host
        amqpPort = config.amqpPort.toString()
        managementPort = config.managementPort.toString()
        vhost = config.vhost
        username = config.username
        useTls = config.useTls
        tlsTruststorePath = config.tlsTruststorePath
        tlsTruststorePassword = config.tlsTruststorePassword
        connectionTimeout = config.connectionTimeout.toString()
        objectScanIntervalMinutes = config.objectScanIntervalMinutes.toString()
        statusScanIntervalSeconds = config.statusScanIntervalSeconds.toString()

        originalPassword = config.password
        password = ""
        passwordChanged = false

        errors = RabbitMqFieldErrors()
        saveError = null
        pendingConfig = null
        saveState = SaveState.Idle
    }

    /** 清掉全部分字段错误：任一字段被编辑时调用（错误在下次提交时重算）。 */
    fun clearErrors() {
        errors = RabbitMqFieldErrors()
    }

    /**
     * 保存链路入口（宿主「完成」→ 本方法），与 Kafka 表单同一套状态机：
     *  1. 校验不过 → 分字段错误留在面板内，返回 false；
     *  2. 校验通过 → 进入 [SaveState.Testing]，按草稿配置发起临时连接测试
     *     （管理 API overview，15 秒超时；busy 经宿主契约禁用「完成」防重复提交）；
     *  3. 成功 → 直接落盘（[doSave]）并回调宿主关窗；
     *  4. 失败 / 超时 → [SaveState.Failed]，面板弹「是否仍要保存」确认框。
     */
    override fun submit(): Boolean {
        if (saveState != SaveState.Idle) return false
        saveError = null
        if (!validate()) return false

        val config = buildConfig()
        saveState = SaveState.Testing
        scope.launch {
            try {
                withTimeout(TEST_TIMEOUT_MS) { RabbitMqModule.testConnection(config) }
                doSave(config)
            } catch (e: TimeoutCancellationException) {
                // 超时不算「正常取消」，按失败处理（确认框可仍要保存）。
                fail(config, stringResource("rabbitmq_ui_test_timeout"))
            } catch (e: CancellationException) {
                // 对话框关闭 / 面板被销毁：正常退出，不落盘、不提示。
                throw e
            } catch (e: Exception) {
                fail(config, stringResource("rabbitmq_ui_test_fail_prefix") + failureMessage(e))
            }
        }
        return false
    }

    /** 用户在确认框选「仍要保存」：按测试时的配置快照落盘。 */
    fun confirmSave() {
        val config = pendingConfig ?: return
        // 先摘掉待保存配置并关掉确认框（它只在 Failed 时渲染）：落盘已改为异步，
        // 不清的话这段窗口里还能再点一次「保存」，写出两条连接。
        pendingConfig = null
        saveState = SaveState.Idle
        saving = true
        scope.launch {
            try {
                doSave(config)
            } finally {
                saving = false
            }
        }
    }

    /** 用户在确认框选「取消」：只关确认框，回到 Idle —— 主对话框保留，表单原样可改。 */
    fun dismissFailed() {
        pendingConfig = null
        saveState = SaveState.Idle
    }

    /** ---------------- 内部 ---------------- */

    private fun fail(config: RabbitMqConfig, message: String) {
        pendingConfig = config
        saveState = SaveState.Failed(message)
    }

    /** 失败原因转提示文案：沿 cause 链取前几条去重拼接（与 Kafka 表单同款）。 */
    private fun failureMessage(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(4)
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("  ←  ")
            .ifBlank { error::class.simpleName ?: "unknown" }

    /**
     * 落盘：新建走 [ConnectionStore.create]、编辑走 [ConnectionStore.update]（core
     * 换发新 UUID：旧连接废弃、新连接接任）。事件广播是 ConnectionStore 的事（落盘
     * 成功自动发 `core.connection.added/updated`），这里不重复发。敏感键（password）
     * 落盘时由 SensitiveOptionsSerializer 自动加密，这里保持明文。
     */
    private suspend fun doSave(config: RabbitMqConfig) {
        val info = ConnectionInfo(
            id = editingId ?: newConnectionId(),
            name = name.ifBlank { defaultName },
            serviceId = "rabbitmq",
            // RabbitMQ 没有天然的「队列管理器名」，mqName 留空。
            mqName = "",
            host = config.host,
            port = config.amqpPort,
            options = config.toOptions(),
        )
        // 写盘（读整份 config.toml + 敏感字段加密 + 原子换文件）走 IO，不压 UI 线程。
        // NonCancellable：点了「完成」之后的落盘不该因对话框关闭而半途作废。
        val result = withContext(Dispatchers.IO + NonCancellable) {
            if (editingId == null) persistence.create(info) else persistence.update(info)
        }
        val failure = result.exceptionOrNull()
        if (failure != null) {
            pendingConfig = null
            saveState = SaveState.Idle
            saveError = failure.message ?: failure::class.simpleName
            return
        }
        pendingConfig = null
        saveState = SaveState.Idle
        savedCallback?.invoke()
    }

    // 真正的存储槽；接口默认实现的 setter 是空操作，不持有它的话宿主赋值会被丢掉。
    private var savedCallback: (() -> Unit)? = null

    override var onSaved: (() -> Unit)?
        get() = savedCallback
        set(value) { savedCallback = value }

    /** 面板内容；布局与动态显示见 [RabbitMqConnectionFormContent]。 */
    @Composable
    override fun Content() = RabbitMqConnectionFormContent(this)
}
