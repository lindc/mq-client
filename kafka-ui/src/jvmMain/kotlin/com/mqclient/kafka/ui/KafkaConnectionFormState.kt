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
import com.mqclient.kafka.kafkaConfigFromOptions
import com.mqclient.kafka.toOptions
import com.mqclient.uicommon.i18n.stringResource

/**
 * 各字段自己的校验错误（null = 没问题）。分字段而不是一条总错误行：
 * 错误就显示在它所属字段的下方（红色小字）。
 */
data class KafkaFieldErrors(
    val bootstrapServers: String? = null,
    val connectionTimeout: String? = null,
) {
    val hasAny: Boolean
        get() = bootstrapServers != null || connectionTimeout != null
}

/**
 * Kafka 连接表单的状态：字段草稿、分字段校验、落盘、编辑模式载入。UI 直接读写
 * 这些状态，业务规则（校验范围、密码保留语义、落盘路径）全部收在本类里。
 *
 * 与 ibm-mq 表单的差异：**同步表单** —— 没有「保存前测试连接」（右键菜单已有
 * 「测试连接」，这里不重复做一套 SaveState 状态机），校验通过直接落盘并返回 true，
 * 宿主随即关窗。密码保留语义与 ibm-mq 一致：编辑模式载入后输入框留空、显示占位符，
 * 用户不动就沿用原值（原值留在 [originalPassword]，构建配置时取回）。
 */
class KafkaConnectionFormState(
    defaultName: String,
) : ConnectionFormState {

    companion object {
        const val TIMEOUT_MIN = 5
        const val TIMEOUT_MAX = 300

        /** securityProtocol 下拉的候选项（与 KafkaConfig.SECURITY_PROTOCOLS 同源）。 */
        val PROTOCOL_OPTIONS = listOf("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL")

        /** SASL 机制候选项。 */
        val SASL_MECHANISM_OPTIONS = listOf("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512")
    }

    /** ---------------- 流程状态 ---------------- */

    var defaultName by mutableStateOf(defaultName); private set
    var errors by mutableStateOf(KafkaFieldErrors()); private set
    var saveError by mutableStateOf<String?>(null); private set

    /** 编辑模式的目标连接 id；null = 新建。 */
    var editingId by mutableStateOf<String?>(null); private set

    /** ---------------- 字段草稿（数字存原字符串，构建时解析）---------------- */

    var name by mutableStateOf(defaultName)
    var bootstrapServers by mutableStateOf("")
    var securityProtocol by mutableStateOf(KafkaConfig.DEFAULT_SECURITY_PROTOCOL)
    var saslMechanism by mutableStateOf(KafkaConfig.DEFAULT_SASL_MECHANISM)
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var connectionTimeout by mutableStateOf(KafkaConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS.toString())

    // 编辑模式的原密码与「用户动过没有」标记：没动过 → 构建时沿用原值，
    // 界面上只显示占位符（见 loadFrom）。
    var originalPassword by mutableStateOf(""); private set
    var passwordChanged by mutableStateOf(false)

    /** 是否 SASL 协议：认证区（用户名 / 密码 / 机制）只在这种协议下显示与生效。 */
    val isSasl: Boolean get() = securityProtocol.startsWith("SASL")

    /** ---------------- 公共动作 ---------------- */

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
        connectionTimeout = connectionTimeout.trim().toIntOrNull()
            ?: KafkaConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS,
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
        connectionTimeout = KafkaConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS.toString()
        originalPassword = ""
        passwordChanged = false
        errors = KafkaFieldErrors()
        saveError = null
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
        connectionTimeout = config.connectionTimeout.toString()

        originalPassword = config.password
        password = ""
        passwordChanged = false

        errors = KafkaFieldErrors()
        saveError = null
    }

    /** 清掉全部分字段错误：任一字段被编辑时调用（错误在下次提交时重算）。 */
    fun clearErrors() {
        errors = KafkaFieldErrors()
    }

    /**
     * 保存链路（同步）：校验 → 落盘。成功返回 true（宿主关窗）；失败（校验不过 /
     * 落盘失败）返回 false，错误已展示在面板内，宿主保持对话框打开。
     */
    override fun submit(): Boolean {
        saveError = null
        if (!validate()) return false
        return doSave(buildConfig())
    }

    /** ---------------- 内部 ---------------- */

    /**
     * 落盘：新建走 [ConnectionStore.create]、编辑走 [ConnectionStore.update]（core
     * 换发新 UUID：旧连接废弃、新连接接任）。事件广播是 ConnectionStore 的事（落盘
     * 成功自动发 `core.connection.added/updated`），这里不重复发。敏感键（password）
     * 落盘时由 SensitiveOptionsSerializer 自动加密，这里保持明文。
     */
    private fun doSave(config: KafkaConfig): Boolean {
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
        val result =
            if (editingId == null) ConnectionStore.create(info) else ConnectionStore.update(info)
        val failure = result.exceptionOrNull()
        if (failure != null) {
            saveError = failure.message ?: failure::class.simpleName
            return false
        }
        savedCallback?.invoke()
        return true
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
