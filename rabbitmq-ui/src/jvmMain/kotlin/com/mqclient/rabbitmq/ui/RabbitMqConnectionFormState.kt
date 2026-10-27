package com.mqclient.rabbitmq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionInfo
import com.mqclient.mqui.state.AbstractConnectionFormState
import com.mqclient.core.ConnectionPersistence
import com.mqclient.core.ConnectionStore
import com.mqclient.core.newConnectionId
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
) : AbstractConnectionFormState<RabbitMqConfig>(scope) {

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

    /** ---------------- 流程状态 ---------------- */

    var defaultName by mutableStateOf(defaultName); private set
    var errors by mutableStateOf(RabbitMqFieldErrors()); private set

    /**
     * 落盘进行中（「仍要保存」路径）：落盘已挪到 IO 线程，这段窗口里必须挡住重复提交，
     * 否则双击会写出两条连接（[ConnectionStore.update] 每次都换发新 id，不会报「已存在」）。
     */

    /** 测试失败待确认的配置快照：用户选「仍要保存」时按它落盘。 */

    /** 编辑模式的目标连接 id；null = 新建。 */

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
    override fun validate(): Boolean {
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
    override fun buildConfig(): RabbitMqConfig = RabbitMqConfig(
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
        resetSaveFlow()
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
        resetSaveFlow()
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
    /* ------------------------- 基类钩子（保存链路） ------------------------- */

    override suspend fun testConnection(config: RabbitMqConfig) {
        RabbitMqModule.testConnection(config)
    }

    override fun testTimeoutMessage(): String = stringResource("rabbitmq_ui_test_timeout")

    override fun testFailurePrefix(): String = stringResource("rabbitmq_ui_test_fail_prefix")

    /**
     * 落盘：新建走 [ConnectionStore.create]、编辑走 [ConnectionStore.update]（core 换发
     * 新 UUID）。写盘要读整份 config.toml + 敏感字段加密 + 原子换文件，走 IO 不压 UI；
     * `NonCancellable` 保证点了「完成」之后的落盘不会因对话框关闭而半途作废。
     */
    override suspend fun persist(config: RabbitMqConfig): Result<Unit> {
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
        return withContext(Dispatchers.IO + NonCancellable) {
            if (editingId == null) persistence.create(info) else persistence.update(info)
        }
    }

    @Composable
    override fun Content() = RabbitMqConnectionFormContent(this)
}
