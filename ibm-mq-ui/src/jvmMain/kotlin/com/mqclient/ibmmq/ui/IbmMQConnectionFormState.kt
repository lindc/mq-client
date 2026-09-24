package com.mqclient.ibmmq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionFormState
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStore
import com.mqclient.ibmmq.ConnectionMode
import com.mqclient.ibmmq.IbmMQConfig
import com.mqclient.ibmmq.IbmMQModule
import com.mqclient.ibmmq.ibmMQConfigFromOptions
import com.mqclient.ibmmq.toOptions
import com.mqclient.uicommon.i18n.stringResource
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** 文件选择器放行的证书扩展名（不含点）。 */
private val CERT_EXTENSIONS = listOf("jks", "p12", "pfx", "kdb")

/**
 * Windows 路径统一成正斜杠：ktoml 0.7.1 的转义器对 `\U`（C:\Users）、`\t`（\trust）
 * 这类「恰好构成 TOML 转义前缀」的序列不转义，写出的 config.toml 自己都解析不了。
 * Java 在 Windows 上原生接受正斜杠（FileInputStream / JSSE / FileDialog 均可），
 * 从边界上避开整个转义问题。
 */
private fun normalizePath(path: String): String = path.replace('\\', '/')

/**
 * 各字段自己的校验错误（null = 没问题）。分字段而不是一条总错误行：
 * 错误就显示在它所属字段的下方（红色小字）。
 */
data class IbmMQFieldErrors(
    val queueManager: String? = null,
    val host: String? = null,
    val port: String? = null,
    val channel: String? = null,
    val ccsid: String? = null,
    val connectionTimeout: String? = null,
    val messageScanInterval: String? = null,
    val objectScanInterval: String? = null,
    val queueDepthWarningThreshold: String? = null,
) {
    val hasAny: Boolean
        get() = queueManager != null || host != null || port != null || channel != null ||
            ccsid != null || connectionTimeout != null ||
            messageScanInterval != null || objectScanInterval != null ||
            queueDepthWarningThreshold != null
}

/**
 * IBM MQ 连接表单的状态：字段草稿、分字段校验、保存链路（[SaveState] 状态机）、
 * 编辑模式载入。UI（[IbmMQConnectionFormContent]）直接读写这些状态，业务规则
 * （校验范围、密码保留语义、落盘路径）全部收在本类里，不散在 Composable 中。
 *
 * 保存链路见 [submit]；编辑模式见 [loadFrom] —— 密码三兄弟载入后只显示占位符，
 * 用户不动就沿用原值（原值留在 `original*` 字段里，构建配置时取回），一动就以
 * 新值为准。编辑模式落盘走 [ConnectionStore.update]（core 换发新 UUID：旧连接废弃、
 * 新连接接任，快照 / 操作历史一 id 一行）。
 */
class IbmMqConnectionFormState(
    defaultName: String,
    private val scope: CoroutineScope,
) : ConnectionFormState {

    companion object {
        /** 「保存前测试连接」的超时：15 秒不返回即按超时处理。 */
        const val TEST_TIMEOUT_MS = 15_000L

        const val PORT_MIN = 1
        const val PORT_MAX = 65535
        const val TIMEOUT_MIN = 5
        const val TIMEOUT_MAX = 300
        const val MSG_INTERVAL_MIN = 3
        const val MSG_INTERVAL_MAX = 3600
        const val OBJ_INTERVAL_MIN = 1
        const val OBJ_INTERVAL_MAX = 1440
        const val THRESHOLD_MIN = 50
        const val THRESHOLD_MAX = 100
    }

    /** ---------------- 宿主契约（busy / onSaved）---------------- */

    override val busy: Boolean get() = saveState == SaveState.Testing

    // 真正的存储槽是 savedCallback；接口默认实现的 setter 是空操作，不 override
    // 的话宿主赋值会被静默丢掉。
    override var onSaved: (() -> Unit)?
        get() = savedCallback
        set(value) { savedCallback = value }

    private var savedCallback: (() -> Unit)? = null

    /** ---------------- 流程状态 ---------------- */

    var defaultName by mutableStateOf(defaultName); private set
    var saveState by mutableStateOf<SaveState>(SaveState.Idle); private set
    var errors by mutableStateOf(IbmMQFieldErrors()); private set
    var saveError by mutableStateOf<String?>(null); private set

    /** 测试失败待确认的配置快照：用户选「仍要保存」时按它落盘。 */
    var pendingConfig by mutableStateOf<IbmMQConfig?>(null); private set

    /** 编辑模式的目标连接 id；null = 新建。 */
    var editingId by mutableStateOf<String?>(null); private set

    /** ---------------- 字段草稿（数字存原字符串，构建时解析）---------------- */

    var name by mutableStateOf(defaultName)
    var mode by mutableStateOf(ConnectionMode.CLIENT)
    var queueManager by mutableStateOf("")
    var host by mutableStateOf("")
    var port by mutableStateOf(IbmMQConfig.DEFAULT_PORT.toString())
    var channel by mutableStateOf(IbmMQConfig.DEFAULT_CHANNEL)
    var ccsid by mutableStateOf("1208")
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var sslEnabled by mutableStateOf(false)
    var trustStorePath by mutableStateOf("")
    var trustStorePassword by mutableStateOf("")
    var keyStorePath by mutableStateOf("")
    var keyStorePassword by mutableStateOf("")
    var cipherSuite by mutableStateOf("")
    var peerName by mutableStateOf("")
    var connectionTimeout by mutableStateOf("30")
    var messageScanInterval by mutableStateOf("10")
    var objectScanInterval by mutableStateOf("60")
    var queueDepthWarningThreshold by mutableStateOf(
        IbmMQConfig.DEFAULT_QUEUE_DEPTH_WARNING_THRESHOLD.toString(),
    )

    // 编辑模式的原密码与「用户动过没有」标记：没动过 → 构建时沿用原值，
    // 界面上只显示占位符（见 loadFrom）。
    var originalPassword by mutableStateOf(""); private set
    var originalTrustStorePassword by mutableStateOf(""); private set
    var originalKeyStorePassword by mutableStateOf(""); private set
    var passwordChanged by mutableStateOf(false)
    var trustStorePasswordChanged by mutableStateOf(false)
    var keyStorePasswordChanged by mutableStateOf(false)

    /** ---------------- 公共动作 ---------------- */

    /** 弹文件选择器挑信任库；取消则不动草稿。对话框在 IO 线程，EDT 不阻塞。 */
    fun pickTrustStore() {
        scope.launch {
            val title = stringResource("ibm_mq_file_dialog_title")
            pickFile(title, CERT_EXTENSIONS)?.let { trustStorePath = normalizePath(it) }
        }
    }

    /** 弹文件选择器挑密钥库；取消则不动草稿。对话框在 IO 线程，EDT 不阻塞。 */
    fun pickKeyStore() {
        scope.launch {
            val title = stringResource("ibm_mq_file_dialog_title")
            pickFile(title, CERT_EXTENSIONS)?.let { keyStorePath = normalizePath(it) }
        }
    }

    /**
     * 分字段校验：错误写进 [errors]（字段下方红字），全部通过返回 true。
     * 网络相关字段只在 CLIENT 模式校验 —— BINDING 模式它们不显示、不参与提交。
     */
    fun validate(): Boolean {
        var e = IbmMQFieldErrors()
        if (queueManager.isBlank()) {
            e = e.copy(queueManager = stringResource("ibm_mq_error_queue_manager_required"))
        }
        if (mode == ConnectionMode.CLIENT) {
            if (host.isBlank()) e = e.copy(host = stringResource("ibm_mq_error_host_required"))
            if (channel.isBlank()) e = e.copy(channel = stringResource("ibm_mq_error_channel_required"))
            val p = port.trim().toIntOrNull()
            if (p == null || p !in PORT_MIN..PORT_MAX) {
                e = e.copy(port = stringResource("ibm_mq_error_port_invalid"))
            }
            if (ccsid.trim().toIntOrNull() == null) {
                e = e.copy(ccsid = stringResource("ibm_mq_error_number_invalid"))
            }
            val t = connectionTimeout.trim().toIntOrNull()
            if (t == null || t !in TIMEOUT_MIN..TIMEOUT_MAX) {
                e = e.copy(connectionTimeout = stringResource("ibm_mq_error_timeout_range"))
            }
        }
        val m = messageScanInterval.trim().toIntOrNull()
        if (m == null || m !in MSG_INTERVAL_MIN..MSG_INTERVAL_MAX) {
            e = e.copy(messageScanInterval = stringResource("ibm_mq_error_msg_interval_range"))
        }
        val o = objectScanInterval.trim().toIntOrNull()
        if (o == null || o !in OBJ_INTERVAL_MIN..OBJ_INTERVAL_MAX) {
            e = e.copy(objectScanInterval = stringResource("ibm_mq_error_obj_interval_range"))
        }
        // 告警阈值按 spec 分三种错误：空 / 非数字 / 超范围（其他数字字段是
        // 一条范围消息兜底 —— 本字段的空值与超范围对用户语义不同，分开展示）。
        val th = queueDepthWarningThreshold.trim()
        val thValue = th.toIntOrNull()
        e =
            when {
                th.isEmpty() ->
                    e.copy(queueDepthWarningThreshold = stringResource("ibm_mq_error_threshold_empty"))
                thValue == null ->
                    e.copy(queueDepthWarningThreshold = stringResource("ibm_mq_error_number_invalid"))
                thValue !in THRESHOLD_MIN..THRESHOLD_MAX ->
                    e.copy(queueDepthWarningThreshold = stringResource("ibm_mq_error_threshold_range"))
                else -> e
            }
        errors = e
        return !e.hasAny
    }

    /**
     * 组装强类型配置：数字字段解析失败回落默认值（边界值已由 [validate] 拦住，
     * 这里的回落只兜「BINDING 模式隐藏字段的默认值」）。
     */
    fun buildConfig(): IbmMQConfig = IbmMQConfig(
        mode = mode,
        queueManager = queueManager.trim(),
        host = host.trim(),
        port = port.trim().toIntOrNull() ?: IbmMQConfig.DEFAULT_PORT,
        channel = channel.trim(),
        ccsid = ccsid.trim().toIntOrNull() ?: 1208,
        username = username.trim(),
        password = resolveSecret(password, originalPassword, passwordChanged),
        sslEnabled = sslEnabled,
        trustStorePath = normalizePath(trustStorePath.trim()),
        trustStorePassword =
            resolveSecret(trustStorePassword, originalTrustStorePassword, trustStorePasswordChanged),
        keyStorePath = normalizePath(keyStorePath.trim()),
        keyStorePassword =
            resolveSecret(keyStorePassword, originalKeyStorePassword, keyStorePasswordChanged),
        cipherSuite = cipherSuite.trim(),
        peerName = peerName.trim(),
        connectionTimeout = connectionTimeout.trim().toIntOrNull() ?: 30,
        messageScanIntervalSeconds = messageScanInterval.trim().toIntOrNull() ?: 10,
        objectScanIntervalMinutes = objectScanInterval.trim().toIntOrNull() ?: 60,
        queueDepthWarningThreshold =
            queueDepthWarningThreshold.trim().toIntOrNull()
                ?: IbmMQConfig.DEFAULT_QUEUE_DEPTH_WARNING_THRESHOLD,
    )

    /** 编辑模式未动过的密码沿用原值；动过 / 新建 → 用输入值。 */
    private fun resolveSecret(current: String, original: String, changed: Boolean): String =
        if (changed || original.isEmpty()) current else original

    /** 清空草稿回新建模式（宿主每次进入创建页都会调）。 */
    override fun reset(defaultName: String) {
        this.defaultName = defaultName
        editingId = null
        mode = ConnectionMode.CLIENT
        queueManager = ""
        host = ""
        port = IbmMQConfig.DEFAULT_PORT.toString()
        channel = IbmMQConfig.DEFAULT_CHANNEL
        ccsid = "1208"
        username = ""
        password = ""
        sslEnabled = false
        trustStorePath = ""
        trustStorePassword = ""
        keyStorePath = ""
        keyStorePassword = ""
        cipherSuite = ""
        peerName = ""
        connectionTimeout = "30"
        messageScanInterval = "10"
        objectScanInterval = "60"
        queueDepthWarningThreshold =
            IbmMQConfig.DEFAULT_QUEUE_DEPTH_WARNING_THRESHOLD.toString()
        originalPassword = ""
        originalTrustStorePassword = ""
        originalKeyStorePassword = ""
        passwordChanged = false
        trustStorePasswordChanged = false
        keyStorePasswordChanged = false
        errors = IbmMQFieldErrors()
        saveError = null
        pendingConfig = null
        saveState = SaveState.Idle
    }

    /**
     * 编辑模式：从已有连接载入草稿。明文配置经 [ibmMQConfigFromOptions] 还原
     * （options 读回时已由序列化器解密）；密码三兄弟不回填明文 —— 输入框留空、
     * 显示占位符，用户不动就沿用原值。
     */
    override fun loadFrom(connection: ConnectionInfo) {
        val config = ibmMQConfigFromOptions(connection.options)
        editingId = connection.id
        defaultName = connection.name
        name = connection.name
        mode = config.mode
        queueManager = config.queueManager
        host = config.host
        port = config.port.toString()
        channel = config.channel
        ccsid = config.ccsid.toString()
        username = config.username
        sslEnabled = config.sslEnabled
        trustStorePath = config.trustStorePath
        keyStorePath = config.keyStorePath
        cipherSuite = config.cipherSuite
        peerName = config.peerName
        connectionTimeout = config.connectionTimeout.toString()
        messageScanInterval = config.messageScanIntervalSeconds.toString()
        objectScanInterval = config.objectScanIntervalMinutes.toString()
        queueDepthWarningThreshold = config.queueDepthWarningThreshold.toString()

        originalPassword = config.password
        originalTrustStorePassword = config.trustStorePassword
        originalKeyStorePassword = config.keyStorePassword
        password = ""
        trustStorePassword = ""
        keyStorePassword = ""
        passwordChanged = false
        trustStorePasswordChanged = false
        keyStorePasswordChanged = false

        errors = IbmMQFieldErrors()
        saveError = null
        pendingConfig = null
        saveState = SaveState.Idle
    }

    /**
     * 保存链路入口（宿主「完成」→ 本方法）：
     *  1. 校验不过 → 分字段错误留在面板内，返回 false（不发连接）；
     *  2. 校验通过 → 进入 [SaveState.Testing]，按当前表单配置发起真实连接
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
        val service = IbmMQModule.serviceOrNull()
        if (service == null) {
            // 模块未初始化（只出现在测试 / 无头环境）：跳过测试直接落盘，
            // 配置对不对由保存后的扫描链路兜底上报。
            doSave(config)
            return false
        }
        saveState = SaveState.Testing
        scope.launch {
            try {
                withTimeout(TEST_TIMEOUT_MS) { service.testConnection(config) }
                doSave(config)
            } catch (e: TimeoutCancellationException) {
                // 超时不算「正常取消」，按失败处理（确认框可仍要保存）。
                fail(config, stringResource("ibm_mq_test_timeout"))
            } catch (e: CancellationException) {
                // 对话框关闭 / 面板被销毁：正常退出，不落盘、不提示。
                throw e
            } catch (e: Exception) {
                fail(config, stringResource("ibm_mq_test_fail_prefix") + failureMessage(e))
            }
        }
        return false
    }

    /** 用户在确认框选「仍要保存」：按测试时的配置快照落盘。 */
    fun confirmSave() {
        pendingConfig?.let(::doSave)
    }

    /** 用户在确认框选「取消」：只关确认框，回到 Idle —— 主对话框保留，表单原样可改。 */
    fun dismissFailed() {
        pendingConfig = null
        saveState = SaveState.Idle
    }

    /** 清掉全部分字段错误：任一字段被编辑时调用（错误在下次提交时重算）。 */
    fun clearErrors() {
        errors = IbmMQFieldErrors()
    }

    /** ---------------- 内部 ---------------- */

    private fun fail(config: IbmMQConfig, message: String) {
        pendingConfig = config
        saveState = SaveState.Failed(message)
    }

    /**
     * 失败原因转提示文案：MQException 顶层消息只有 `MQJE001...Reason xxxx` 一行，
     * 真正的细节（本地映射失败 / 服务端 AMQ9641 拒绝）在 cause 链里 —— 沿链取前
     * 几条去重拼起来，确认框里才能看出到底是谁在拒绝。
     */
    private fun failureMessage(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(4)
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("  ←  ")
            .ifBlank { error::class.simpleName ?: "unknown" }

    /**
     * 落盘 + 收尾：新建走 [ConnectionStore.create]、编辑走 [ConnectionStore.update]
     * （同 id 原位替换，serviceId 不变）。事件广播是 ConnectionStore 的事（落盘
     * 成功自动发 `core.connection.added/updated`），这里不重复发。敏感键
     * （password / trustStorePassword / keyStorePassword）落盘时由
     * SensitiveOptionsSerializer 自动加密，这里保持明文。
     */
    private fun doSave(config: IbmMQConfig) {
        val info = ConnectionInfo(
            id = editingId ?: UUID.randomUUID().toString(),
            name = name.ifBlank { defaultName },
            serviceId = "ibm-mq",
            // 队列管理器就是 IBM MQ 端点的名字，侧边栏副标题 / endpoint 用得上。
            mqName = config.queueManager,
            host = config.host,
            port = config.port,
            options = config.toOptions(),
        )
        val result =
            if (editingId == null) ConnectionStore.create(info) else ConnectionStore.update(info)
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

    /** 面板内容；布局与动态显示见 [IbmMQConnectionFormContent]。 */
    @Composable
    override fun Content() = IbmMQConnectionFormContent(this)
}
