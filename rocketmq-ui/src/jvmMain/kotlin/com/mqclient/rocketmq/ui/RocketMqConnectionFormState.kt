package com.mqclient.rocketmq.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionFormState
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStore
import com.mqclient.core.newConnectionId
import com.mqclient.mqui.state.SaveState
import com.mqclient.rocketmq.RocketMqConfig
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.rocketMqConfigFromOptions
import com.mqclient.rocketmq.toOptions
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * RocketMQ 连接表单的状态：字段草稿、分字段校验、保存链路（[SaveState] 状态机），
 * 与 RabbitMq / Kafka 表单同一套。
 *
 * 保存链路：**同步表单改异步** —— 「完成」先按草稿配置发起一次临时连接测试
 * （cluster info 验证，15 秒超时；busy 经宿主契约禁用「完成」），成功直接落盘并
 * 回调宿主关窗；失败弹「是否仍要保存」确认框（SaveAnywayDialog）。
 *
 * 敏感语义与 Kafka 一致：secretKey 编辑模式不回填明文，用户不动就沿用原值。
 * host 顶层字段存 NameServer 首地址（展示口径），完整多地址在 options.namesrvAddr。
 */
class RocketMqConnectionFormState(
    defaultName: String,
    private val scope: CoroutineScope,
) : ConnectionFormState {

    companion object {
        const val TEST_TIMEOUT_MS = 15_000L
        const val TIMEOUT_MIN = 5
        const val TIMEOUT_MAX = 300
        const val OBJECT_SCAN_MIN = 1
        const val OBJECT_SCAN_MAX = 1440
        const val STATUS_SCAN_MIN = 5
        const val STATUS_SCAN_MAX = 3600
    }

    override val busy: Boolean get() = saveState == SaveState.Testing

    var defaultName by mutableStateOf(defaultName); private set
    var errors by mutableStateOf(RocketMqFieldErrors()); private set
    var saveError by mutableStateOf<String?>(null); private set
    var saveState by mutableStateOf<SaveState>(SaveState.Idle); private set

    var pendingConfig by mutableStateOf<RocketMqConfig?>(null); private set
    var editingId by mutableStateOf<String?>(null); private set

    var name by mutableStateOf(defaultName)
    var namesrvAddr by mutableStateOf("")
    var accessKey by mutableStateOf("")
    var secretKey by mutableStateOf("")
    var connectionTimeout by mutableStateOf(RocketMqConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS.toString())
    var objectScanIntervalMinutes by mutableStateOf(RocketMqConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES.toString())
    var statusScanIntervalSeconds by mutableStateOf(RocketMqConfig.DEFAULT_STATUS_SCAN_INTERVAL_SECONDS.toString())

    var originalSecretKey by mutableStateOf(""); private set
    var secretKeyChanged by mutableStateOf(false)

    fun validate(): Boolean {
        var e = RocketMqFieldErrors()
        if (namesrvAddr.isBlank()) {
            e = e.copy(namesrvAddr = stringResource("rocketmq_ui_error_namesrv_required"))
        }
        val t = connectionTimeout.trim().toIntOrNull()
        if (t == null || t !in TIMEOUT_MIN..TIMEOUT_MAX) {
            e = e.copy(connectionTimeout = stringResource("rocketmq_ui_error_timeout_range"))
        }
        val o = objectScanIntervalMinutes.trim().toIntOrNull()
        if (o == null || o !in OBJECT_SCAN_MIN..OBJECT_SCAN_MAX) {
            e = e.copy(objectScanInterval = stringResource("rocketmq_ui_error_object_scan_range"))
        }
        val s = statusScanIntervalSeconds.trim().toIntOrNull()
        if (s == null || s !in STATUS_SCAN_MIN..STATUS_SCAN_MAX) {
            e = e.copy(statusScanInterval = stringResource("rocketmq_ui_error_status_scan_range"))
        }
        errors = e
        return !e.hasAny
    }

    fun buildConfig(): RocketMqConfig = RocketMqConfig(
        namesrvAddr = namesrvAddr.trim(),
        accessKey = accessKey.trim(),
        secretKey = if (secretKeyChanged || originalSecretKey.isEmpty()) secretKey else originalSecretKey,
        connectionTimeout = connectionTimeout.trim().toIntOrNull()
            ?: RocketMqConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS,
        objectScanIntervalMinutes = objectScanIntervalMinutes.trim().toIntOrNull()
            ?: RocketMqConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES,
        statusScanIntervalSeconds = statusScanIntervalSeconds.trim().toIntOrNull()
            ?: RocketMqConfig.DEFAULT_STATUS_SCAN_INTERVAL_SECONDS,
    )

    override fun reset(defaultName: String) {
        this.defaultName = defaultName
        editingId = null
        namesrvAddr = ""
        accessKey = ""
        secretKey = ""
        connectionTimeout = RocketMqConfig.DEFAULT_CONNECTION_TIMEOUT_SECONDS.toString()
        objectScanIntervalMinutes = RocketMqConfig.DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES.toString()
        statusScanIntervalSeconds = RocketMqConfig.DEFAULT_STATUS_SCAN_INTERVAL_SECONDS.toString()
        originalSecretKey = ""
        secretKeyChanged = false
        errors = RocketMqFieldErrors()
        saveError = null
        pendingConfig = null
        saveState = SaveState.Idle
    }

    override fun loadFrom(connection: ConnectionInfo) {
        val config = rocketMqConfigFromOptions(connection.options)
        editingId = connection.id
        defaultName = connection.name
        name = connection.name
        namesrvAddr = config.namesrvAddr
        accessKey = config.accessKey
        connectionTimeout = config.connectionTimeout.toString()
        objectScanIntervalMinutes = config.objectScanIntervalMinutes.toString()
        statusScanIntervalSeconds = config.statusScanIntervalSeconds.toString()

        originalSecretKey = config.secretKey
        secretKey = ""
        secretKeyChanged = false

        errors = RocketMqFieldErrors()
        saveError = null
        pendingConfig = null
        saveState = SaveState.Idle
    }

    fun clearErrors() {
        errors = RocketMqFieldErrors()
    }

    override fun submit(): Boolean {
        if (saveState != SaveState.Idle) return false
        saveError = null
        if (!validate()) return false

        val config = buildConfig()
        saveState = SaveState.Testing
        scope.launch {
            try {
                withTimeout(TEST_TIMEOUT_MS) { RocketMqModule.testConnection(config) }
                doSave(config)
            } catch (e: TimeoutCancellationException) {
                fail(config, stringResource("rocketmq_ui_test_timeout"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(config, stringResource("rocketmq_ui_test_fail_prefix") + failureMessage(e))
            }
        }
        return false
    }

    fun confirmSave() {
        pendingConfig?.let(::doSave)
    }

    fun dismissFailed() {
        pendingConfig = null
        saveState = SaveState.Idle
    }

    private fun fail(config: RocketMqConfig, message: String) {
        pendingConfig = config
        saveState = SaveState.Failed(message)
    }

    private fun failureMessage(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(4)
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .distinct()
            .joinToString("  ←  ")
            .ifBlank { error::class.simpleName ?: "unknown" }

    private fun doSave(config: RocketMqConfig) {
        val info = ConnectionInfo(
            id = editingId ?: newConnectionId(),
            name = name.ifBlank { defaultName },
            serviceId = "rocketmq",
            mqName = "",
            // 顶层 host / port 是展示口径：NameServer 首地址 + 9876。
            host = config.namesrvAddr.substringBefore(";").substringBefore(":"),
            port = 9876,
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

    private var savedCallback: (() -> Unit)? = null

    override var onSaved: (() -> Unit)?
        get() = savedCallback
        set(value) { savedCallback = value }

    @Composable
    override fun Content() = RocketMqConnectionFormContent(this)
}

/** 各字段自己的校验错误（null = 没问题）。 */
data class RocketMqFieldErrors(
    val namesrvAddr: String? = null,
    val connectionTimeout: String? = null,
    val objectScanInterval: String? = null,
    val statusScanInterval: String? = null,
) {
    val hasAny: Boolean
        get() = namesrvAddr != null ||
            connectionTimeout != null ||
            objectScanInterval != null ||
            statusScanInterval != null
}
