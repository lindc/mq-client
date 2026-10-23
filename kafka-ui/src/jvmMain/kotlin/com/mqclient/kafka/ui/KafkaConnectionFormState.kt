package com.mqclient.kafka.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionFormState
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStore
import com.mqclient.core.newConnectionId
import com.mqclient.kafka.KafkaConfig
import com.mqclient.kafka.KafkaModule
import com.mqclient.kafka.kafkaConfigFromOptions
import com.mqclient.kafka.toOptions
import com.mqclient.mqui.state.SaveState
import com.mqclient.uicommon.file.normalizePath
import com.mqclient.uicommon.file.pickFile
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** 文件选择器放行的证书扩展名（不含点）；Kafka 场景没有 MQ 的 kdb，故比 MQ 少一项。 */
private val CERT_EXTENSIONS = listOf("p12", "pfx", "jks")

/**
 * 各字段自己的校验错误（null = 没问题）。分字段而不是一条总错误行：
 * 错误就显示在它所属字段的下方（红色小字）。
 */
data class KafkaFieldErrors(
    val bootstrapServers: String? = null,
    val connectionTimeout: String? = null,
    val objectScanInterval: String? = null,
    val lagScanInterval: String? = null,
    val lagWarningThreshold: String? = null,
) {
    val hasAny: Boolean
        get() = bootstrapServers != null ||
            connectionTimeout != null ||
            objectScanInterval != null ||
            lagScanInterval != null ||
            lagWarningThreshold != null
}

/**
 * Kafka 连接表单的状态：字段草稿、分字段校验、保存链路（[SaveState] 状态机，与
 * IBM MQ 表单同一套）、编辑模式载入。UI 直接读写这些状态，业务规则（校验范围、
 * 密码保留语义、落盘路径）全部收在本类里。
 *
 * 保存链路与 IBM MQ 完全一致：**同步表单改异步** —— 「完成」先按草稿配置发起
 * 一次临时连接测试（describeCluster，15 秒超时；busy 经宿主契约禁用「完成」），
 * 成功直接落盘并回调宿主关窗；失败弹「是否仍要保存」确认框（[SaveAnywayDialog]），
 * 「仍要保存」按测试时的配置快照落盘，「取消」回 Idle 留在向导里。
 *
 * 密码保留语义与 IBM MQ 一致：编辑模式载入后输入框留空、显示占位符，用户不动就
 * 沿用原值（原值留在 original* 字段，构建配置时取回）。
 */
class KafkaConnectionFormState(
    defaultName: String,
    private val scope: CoroutineScope,
) : ConnectionFormState {

    companion object {
        /** 「保存前测试连接」的超时：与 IBM MQ 表单同款，15 秒不返回按超时处理。 */
        const val TEST_TIMEOUT_MS = 15_000L

        const val TIMEOUT_MIN = 5
        const val TIMEOUT_MAX = 300

        /** 对象慢扫间隔的合法范围（分钟）。 */
        const val OBJECT_SCAN_MIN = 1
        const val OBJECT_SCAN_MAX = 1440

        /** Lag 心跳间隔的合法范围（秒）。 */
        const val LAG_SCAN_MIN = 10
        const val LAG_SCAN_MAX = 3600

        /** Lag 告警阈值：0（不告警）或正整数（Long）。 */
        const val LAG_THRESHOLD_MIN = 0L

        /** securityProtocol 下拉的候选项（与 KafkaConfig.SECURITY_PROTOCOLS 同源）。 */
        val PROTOCOL_OPTIONS = listOf("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL")

        /** SASL 机制候选项。 */
        val SASL_MECHANISM_OPTIONS = listOf("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512")
    }

    /** ---------------- 宿主契约（busy / onSaved）---------------- */

    override val busy: Boolean get() = saveState == SaveState.Testing || saving

    /** ---------------- 流程状态 ---------------- */

    var defaultName by mutableStateOf(defaultName); private set
    var errors by mutableStateOf(KafkaFieldErrors()); private set
    var saveError by mutableStateOf<String?>(null); private set
    var saveState by mutableStateOf<SaveState>(SaveState.Idle); private set

    /**
     * 落盘进行中（「仍要保存」路径）：落盘已挪到 IO 线程，这段窗口里必须挡住重复提交，
     * 否则双击会写出两条连接（[ConnectionStore.update] 每次都换发新 id，不会报「已存在」）。
     */
    private var saving by mutableStateOf(false)

    /** 测试失败待确认的配置快照：用户选「仍要保存」时按它落盘。 */
    var pendingConfig by mutableStateOf<KafkaConfig?>(null); private set

    /** 编辑模式的目标连接 id；null = 新建。 */
    var editingId by mutableStateOf<String?>(null); private set

    /** ---------------- 字段草稿（数字存原字符串，构建时解析）---------------- */

    var name by mutableStateOf(defaultName)
    var bootstrapServers by mutableStateOf("")
    var securityProtocol by mutableStateOf(KafkaConfig.DEFAULT_SECURITY_PROTOCOL)
    var saslMechanism by mutableStateOf(KafkaConfig.DEFAULT_SASL_MECHANISM)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var truststorePath by mutableStateOf("")
    var truststorePassword by mutableStateOf("")
    var connectionTimeout by mutableStateOf(KafkaConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS.toString())
    var objectScanIntervalMinutes by mutableStateOf(KafkaConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES.toString())
    var lagScanIntervalSeconds by mutableStateOf(KafkaConfig.DEFAULT_LAG_SCAN_INTERVAL_SECONDS.toString())
    var lagWarningThreshold by mutableStateOf(KafkaConfig.DEFAULT_LAG_WARNING_THRESHOLD.toString())

    // 编辑模式的原密码与「用户动过没有」标记：没动过 → 构建时沿用原值，
    // 界面上只显示占位符（见 loadFrom）。SASL 密码与 truststore 密码各一套。
    var originalPassword by mutableStateOf(""); private set
    var passwordChanged by mutableStateOf(false)
    var originalTruststorePassword by mutableStateOf(""); private set
    var truststorePasswordChanged by mutableStateOf(false)

    /** 是否 SASL 协议：认证区（用户名 / 密码 / 机制）只在这种协议下显示与生效。 */
    val isSasl: Boolean get() = securityProtocol.startsWith("SASL")

    /** 是否 TLS 协议：信任库区只在这种协议下显示与生效。 */
    val isTls: Boolean get() = securityProtocol == "SSL" || securityProtocol == "SASL_SSL"

    /** ---------------- 公共动作 ---------------- */

    /** 弹文件选择器挑信任库；取消则不动草稿。对话框在 IO 线程，EDT 不阻塞。 */
    fun pickTrustStore() {
        scope.launch {
            val title = stringResource("kafka_ui_file_dialog_title")
            pickFile(title, CERT_EXTENSIONS)?.let { truststorePath = normalizePath(it) }
        }
    }

    /** 分字段校验：错误写进 [errors]（字段下方红字），全部通过返回 true。 */
    fun validate(): Boolean {
        var e = KafkaFieldErrors()
        if (bootstrapServers.isBlank()) {
            e = e.copy(bootstrapServers = stringResource("kafka_ui_error_bootstrap_required"))
        }
        val t = connectionTimeout.trim().toIntOrNull()
        if (t == null || t !in TIMEOUT_MIN..TIMEOUT_MAX) {
            e = e.copy(connectionTimeout = stringResource("kafka_ui_error_timeout_range"))
        }
        val o = objectScanIntervalMinutes.trim().toIntOrNull()
        if (o == null || o !in OBJECT_SCAN_MIN..OBJECT_SCAN_MAX) {
            e = e.copy(objectScanInterval = stringResource("kafka_ui_error_object_scan_range"))
        }
        val l = lagScanIntervalSeconds.trim().toIntOrNull()
        if (l == null || l !in LAG_SCAN_MIN..LAG_SCAN_MAX) {
            e = e.copy(lagScanInterval = stringResource("kafka_ui_error_lag_scan_range"))
        }
        val w = lagWarningThreshold.trim().toLongOrNull()
        if (w == null || w < LAG_THRESHOLD_MIN) {
            e = e.copy(lagWarningThreshold = stringResource("kafka_ui_error_lag_threshold_range"))
        }
        errors = e
        return !e.hasAny
    }

    /** 组装强类型配置：数字字段解析失败回落默认值（边界已由 [validate] 拦住）。 */
    fun buildConfig(): KafkaConfig = KafkaConfig(
        bootstrapServers = bootstrapServers.trim(),
        securityProtocol = securityProtocol,
        saslMechanism = saslMechanism,
        username = username.trim(),
        password = if (passwordChanged || originalPassword.isEmpty()) password else originalPassword,
        truststorePath = truststorePath.trim(),
        truststorePassword =
            if (truststorePasswordChanged || originalTruststorePassword.isEmpty()) truststorePassword
            else originalTruststorePassword,
        connectionTimeout = connectionTimeout.trim().toIntOrNull()
            ?: KafkaConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS,
        objectScanIntervalMinutes = objectScanIntervalMinutes.trim().toIntOrNull()
            ?: KafkaConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES,
        lagScanIntervalSeconds = lagScanIntervalSeconds.trim().toIntOrNull()
            ?: KafkaConfig.DEFAULT_LAG_SCAN_INTERVAL_SECONDS,
        lagWarningThreshold = lagWarningThreshold.trim().toLongOrNull()
            ?: KafkaConfig.DEFAULT_LAG_WARNING_THRESHOLD,
    )

    /** 清空草稿回新建模式（宿主每次进入创建页都会调）。 */
    override fun reset(defaultName: String) {
        this.defaultName = defaultName
        editingId = null
        bootstrapServers = ""
        securityProtocol = KafkaConfig.DEFAULT_SECURITY_PROTOCOL
        saslMechanism = KafkaConfig.DEFAULT_SASL_MECHANISM
        username = ""
        password = ""
        truststorePath = ""
        truststorePassword = ""
        connectionTimeout = KafkaConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS.toString()
        objectScanIntervalMinutes = KafkaConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES.toString()
        lagScanIntervalSeconds = KafkaConfig.DEFAULT_LAG_SCAN_INTERVAL_SECONDS.toString()
        lagWarningThreshold = KafkaConfig.DEFAULT_LAG_WARNING_THRESHOLD.toString()
        originalPassword = ""
        passwordChanged = false
        originalTruststorePassword = ""
        truststorePasswordChanged = false
        errors = KafkaFieldErrors()
        saveError = null
        pendingConfig = null
        saveState = SaveState.Idle
    }

    /**
     * 编辑模式：从已有连接载入草稿。明文配置经 [kafkaConfigFromOptions] 还原
     * （options 读回时已由序列化器解密）；密码不回填明文 —— 输入框留空、显示
     * 占位符，用户不动就沿用原值。
     */
    override fun loadFrom(connection: ConnectionInfo) {
        val config = kafkaConfigFromOptions(connection.options)
        editingId = connection.id
        defaultName = connection.name
        name = connection.name
        bootstrapServers = config.bootstrapServers
        securityProtocol = config.securityProtocol
        saslMechanism = config.saslMechanism
        username = config.username
        truststorePath = config.truststorePath
        connectionTimeout = config.connectionTimeout.toString()
        objectScanIntervalMinutes = config.objectScanIntervalMinutes.toString()
        lagScanIntervalSeconds = config.lagScanIntervalSeconds.toString()
        lagWarningThreshold = config.lagWarningThreshold.toString()

        originalPassword = config.password
        password = ""
        passwordChanged = false
        originalTruststorePassword = config.truststorePassword
        truststorePassword = ""
        truststorePasswordChanged = false

        errors = KafkaFieldErrors()
        saveError = null
        pendingConfig = null
        saveState = SaveState.Idle
    }

    /** 清掉全部分字段错误：任一字段被编辑时调用（错误在下次提交时重算）。 */
    fun clearErrors() {
        errors = KafkaFieldErrors()
    }

    /**
     * 保存链路入口（宿主「完成」→ 本方法），与 IBM MQ 表单同一套状态机：
     *  1. 校验不过 → 分字段错误留在面板内，返回 false；
     *  2. 校验通过 → 进入 [SaveState.Testing]，按草稿配置发起临时连接测试
     *     （15 秒超时；busy 经宿主契约禁用「完成」防重复提交）；
     *  3. 成功 → 直接落盘（[doSave]）并回调宿主关窗；
     *  4. 失败 / 超时 → [SaveState.Failed]，面板弹「是否仍要保存」确认框。
     *
     * 全程返回 false（测试是慢操作，无法同步给最终结果；宿主对 false 的唯一
     * 反应就是保持对话框打开）。
     */
    override fun submit(): Boolean {
        if (saveState != SaveState.Idle) return false
        saveError = null
        if (!validate()) return false

        val config = buildConfig()
        saveState = SaveState.Testing
        scope.launch {
            try {
                withTimeout(TEST_TIMEOUT_MS) { KafkaModule.testConnection(config) }
                doSave(config)
            } catch (e: TimeoutCancellationException) {
                // 超时不算「正常取消」，按失败处理（确认框可仍要保存）。
                fail(config, stringResource("kafka_ui_test_timeout"))
            } catch (e: CancellationException) {
                // 对话框关闭 / 面板被销毁：正常退出，不落盘、不提示。
                throw e
            } catch (e: Exception) {
                fail(config, stringResource("kafka_ui_test_fail_prefix") + failureMessage(e))
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

    private fun fail(config: KafkaConfig, message: String) {
        pendingConfig = config
        saveState = SaveState.Failed(message)
    }

    /**
     * 失败原因转提示文案：沿 cause 链取前几条去重拼接（KafkaFuture 的
     * ExecutionException 包装会把真正的错误藏在里面），确认框里才能看出根因。
     */
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
    private suspend fun doSave(config: KafkaConfig) {
        // host / port 取第一个 broker（endpoint 只是展示用途）；无端口的条目按
        // Kafka 客户端同款默认 9092。
        val first = config.bootstrapServers.split(",").firstOrNull()?.trim().orEmpty()
        val host = first.substringBefore(":")
        val port = first.substringAfter(":", "").toIntOrNull() ?: 9092
        val info = ConnectionInfo(
            id = editingId ?: newConnectionId(),
            name = name.ifBlank { defaultName },
            serviceId = "kafka",
            // Kafka 没有天然的「队列管理器名」，mqName 留空。
            mqName = "",
            host = host,
            port = port,
            options = config.toOptions(),
        )
        // 写盘（读整份 config.toml + 敏感字段加密 + 原子换文件）走 IO，不压 UI 线程。
        // NonCancellable：点了「完成」之后的落盘不该因对话框关闭而半途作废。
        val result = withContext(Dispatchers.IO + NonCancellable) {
            if (editingId == null) ConnectionStore.create(info) else ConnectionStore.update(info)
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

    /** 面板内容；布局与动态显示见 [KafkaConnectionFormContent]。 */
    @Composable
    override fun Content() = KafkaConnectionFormContent(this)
}
