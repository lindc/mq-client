package com.mqclient.starrocks.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionPersistence
import com.mqclient.core.newConnectionId
import com.mqclient.mqui.state.AbstractConnectionFormState
import com.mqclient.olap.base.connection.HealthReport
import com.mqclient.starrocks.StarRocksConnectionConfig
import com.mqclient.starrocks.StarRocksConstants
import com.mqclient.starrocks.StarRocksModule
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 分字段校验错误（存 i18n key，展示时由面板翻译）。 */
data class StarRocksFieldErrors(
    val host: String? = null,
    val port: String? = null,
    val user: String? = null,
    val database: String? = null,
    /** SSH 段整体错误（地址 / 端口 / 用户名任一不合格）。 */
    val ssh: String? = null,
) {
    /** 是否任一字段有错。 */
    val hasAny: Boolean get() = listOf(host, port, user, database, ssh).any { it != null }
}

/**
 * StarRocks 连接表单状态：字段草稿 + 复用 mq-ui 的保存状态机。
 *
 * 与 IBM MQ / Kafka / RabbitMQ / RocketMQ 四套表单**完全同构**：表单本身没有
 * 「测试连接」/「保存」按钮，宿主 footer 的「完成」调基类的
 * [submit][com.mqclient.core.ConnectionFormState.submit] ——
 * 校验 → 临时连接测试（15s 超时，`busy` 禁用「完成」）→ 成功落盘并 `onSaved`
 * 关窗 / 失败由面板弹 [com.mqclient.mqui.state.SaveAnywayDialog]
 * （「是否仍要保存」）。错误从不出面板边界，宿主只认布尔值。
 *
 * 字段草稿由 `mutableStateOf` 持有、经 `rememberFormState` 的 `remember { }`
 * 缓存；连接名初始为空，靠 placeholder 提示（不硬编码「连接 N」）。
 *
 * @param probe 注入的连接探针（默认走 [StarRocksModule] 的 testConnection，单测传 fake）。
 * @param persistence 落盘端口（默认直连 `ConnectionStore`）。
 * @param ioDispatcher 落盘线程（默认 IO；单测注入 Unconfined 以在虚拟时间下可预测）。
 */
class StarRocksConnectionFormState(
    defaultName: String,
    scope: CoroutineScope,
    private val probe: suspend (StarRocksConnectionConfig) -> HealthReport =
        { StarRocksModule.testConnection(it) },
    private val persistence: ConnectionPersistence = ConnectionPersistence.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AbstractConnectionFormState<StarRocksConnectionConfig>(scope) {

    /** 宿主算出的默认连接名（只在用户没填时用于落盘）。 */
    var defaultName by mutableStateOf(defaultName)
        private set

    /** 连接名（初始为空 → 输入框显示 placeholder）。 */
    var name by mutableStateOf("")

    /** 主机。 */
    var host by mutableStateOf(StarRocksConnectionConfig.DEFAULT_HOST)

    /** 端口（原字符串，构建时解析）。 */
    var port by mutableStateOf(StarRocksConnectionConfig.DEFAULT_PORT.toString())

    /** 用户名。 */
    var user by mutableStateOf(StarRocksConnectionConfig.DEFAULT_USER)

    /** 密码（编辑模式下由用户输入；未动过则沿用 [originalPassword]）。 */
    var password by mutableStateOf("")

    /**
     * 编辑模式下的原始密码。
     *
     * 为什么要这层：`loadFrom` 把密码框留空（不把明文回填到 UI），如果 `buildConfig`
     * 直接用输入框的值，**打开编辑 → 不改密码 → 保存**就会把已存密码覆盖成空串，
     * 连接随即失效。IBM MQ 的表单用 `resolveSecret` 处理同一问题，这里对齐。
     */
    private var originalPassword by mutableStateOf("")

    /** 用户是否动过密码框（动过才用输入值）。 */
    private var passwordChanged by mutableStateOf(false)

    /** 默认库。 */
    var database by mutableStateOf(StarRocksConnectionConfig.DEFAULT_DATABASE)

    /* ------------------------------ SSH 隧道（可选） ------------------------------ */

    /** 是否经 SSH 隧道连接（生产环境 FE 一般不直接暴露 9030）。 */
    var sshEnabled by mutableStateOf(false)

    /** 堡垒机地址。 */
    var sshHost by mutableStateOf("")

    /** SSH 端口（原字符串，构建时解析）。 */
    var sshPort by mutableStateOf(StarRocksConnectionConfig.DEFAULT_SSH_PORT.toString())

    /** SSH 用户名。 */
    var sshUser by mutableStateOf("")

    /**
     * SSH 密码；填了私钥时它作为私钥口令（passphrase）。
     *
     * 一个字段承担两种职责是刻意的：OpenSSH 用户的心智就是「密码或私钥+口令」，
     * 拆成两个框会让人不知道该填哪个。
     */
    var sshPassword by mutableStateOf("")

    /** 编辑模式下的原始 SSH 密码（未动过则沿用）。 */
    private var originalSshPassword by mutableStateOf("")

    /** 用户是否动过 SSH 密码框。 */
    private var sshPasswordChanged by mutableStateOf(false)

    /** SSH 私钥路径（空 = 用密码）。 */
    var sshKeyPath by mutableStateOf("")

    /** 编辑模式未动过的密码沿用原值；动过 / 新建 → 用输入值。 */
    private fun resolveSecret(current: String, original: String, changed: Boolean): String =
        if (changed || original.isEmpty()) current else original

    /** 密码框变更：记下"动过"，否则编辑保存会覆盖成空串。 */
    fun onPasswordChange(value: String) {
        password = value
        passwordChanged = true
        clearErrors()
    }

    /** SSH 密码框变更（同上）。 */
    fun onSshPasswordChange(value: String) {
        sshPassword = value
        sshPasswordChanged = true
        clearErrors()
    }

    /** SSH 端口输入只留数字。 */
    fun onSshPortChange(value: String) {
        sshPort = value.filter(Char::isDigit).take(5)
        clearErrors()
    }

    /** 分字段校验错误。 */
    var errors by mutableStateOf(StarRocksFieldErrors())
        private set

    /** 清掉全部字段错误（字段变更时调用）。 */
    fun clearErrors() {
        errors = StarRocksFieldErrors()
    }

    /** 端口输入只留数字（桌面端物理键盘不受 KeyboardType 限制）。 */
    fun onPortChange(value: String) {
        port = value.filter(Char::isDigit).take(5)
        clearErrors()
    }

    /* ------------------------------ 宿主契约 ------------------------------ */

    /** 清空草稿回到初始值（宿主每次进入创建页调用）；连接名保持空。 */
    override fun reset(defaultName: String) {
        this.defaultName = defaultName
        name = ""
        host = StarRocksConnectionConfig.DEFAULT_HOST
        port = StarRocksConnectionConfig.DEFAULT_PORT.toString()
        user = StarRocksConnectionConfig.DEFAULT_USER
        password = ""
        database = StarRocksConnectionConfig.DEFAULT_DATABASE
        sshEnabled = false
        sshHost = ""
        sshPort = StarRocksConnectionConfig.DEFAULT_SSH_PORT.toString()
        sshUser = ""
        sshPassword = ""
        originalSshPassword = ""
        sshPasswordChanged = false
        sshKeyPath = ""
        errors = StarRocksFieldErrors()
        resetSaveFlow()
    }

    /** 从已有连接预填（编辑模式）；密码留空，用户不动就沿用原值。 */
    override fun loadFrom(connection: ConnectionInfo) {
        val config = StarRocksConnectionConfig.fromOptions(
            connectionId = connection.id,
            displayName = connection.name,
            options = connection.options,
        )
        editingId = connection.id
        name = connection.name
        host = config.host
        port = config.port.toString()
        user = config.user
        password = ""
        originalPassword = config.password
        passwordChanged = false
        database = config.database
        // SSH 段同样回填：编辑一个「经堡垒机连接」的配置时，若这些字段空掉，
        // 用户点保存就会静默退化成直连 —— 那是最难排查的一类配置丢失。
        sshEnabled = config.sshEnabled
        sshHost = config.sshHost
        sshPort = config.sshPort.toString()
        sshUser = config.sshUser
        sshPassword = ""
        originalSshPassword = config.sshPassword
        sshPasswordChanged = false
        sshKeyPath = config.sshKeyPath
        errors = StarRocksFieldErrors()
        resetSaveFlow()
    }

    /** 面板内容（字段 + 测试状态 + 确认框）。 */
    @Composable
    override fun Content() = StarRocksConnectionFormContent(this)

    /* ------------------------------ 基类钩子 ------------------------------ */

    /** 校验：错误写成分字段 i18n key，展示时翻译。 */
    override fun validate(): Boolean {
        val parsedPort = port.toIntOrNull()
        errors = StarRocksFieldErrors(
            host = if (host.isBlank()) "olap.starrocks.form.err_host" else null,
            port = if (parsedPort == null || parsedPort !in 1..65535) {
                "olap.starrocks.form.err_port"
            } else null,
            user = if (user.isBlank()) "olap.starrocks.form.err_user" else null,
            database = if (database.isBlank()) "olap.starrocks.form.err_database" else null,
            ssh = if (!sshEnabled) {
                null
            } else {
                val sshPortValue = sshPort.toIntOrNull()
                when {
                    sshHost.isBlank() -> "olap.starrocks.form.err_ssh_host"
                    sshPortValue == null || sshPortValue !in 1..65535 -> "olap.starrocks.form.err_ssh_port"
                    sshUser.isBlank() -> "olap.starrocks.form.err_ssh_user"
                    // 凭据二选一：既没有私钥也没有密码（新建时）无法认证。
                    sshKeyPath.isBlank() &&
                        sshPassword.isBlank() && originalSshPassword.isBlank() ->
                        "olap.starrocks.form.err_ssh_credential"
                    else -> null
                }
            },
        )
        return !errors.hasAny
    }

    /** 从草稿构建不可变配置快照。 */
    override fun buildConfig(): StarRocksConnectionConfig = StarRocksConnectionConfig(
        connectionId = editingId ?: newConnectionId(),
        displayName = name.ifBlank { defaultName },
        host = host.trim(),
        port = port.toIntOrNull() ?: StarRocksConnectionConfig.DEFAULT_PORT,
        user = user.trim(),
        password = resolveSecret(password, originalPassword, passwordChanged),
        database = database.trim(),
        sshEnabled = sshEnabled,
        sshHost = sshHost.trim(),
        sshPort = sshPort.toIntOrNull() ?: StarRocksConnectionConfig.DEFAULT_SSH_PORT,
        sshUser = sshUser.trim(),
        sshPassword = resolveSecret(sshPassword, originalSshPassword, sshPasswordChanged),
        sshKeyPath = sshKeyPath.trim(),
    )

    /**
     * 临时连接测试：复用 [StarRocksModule.testConnection]。
     * 基类契约要求「失败抛异常」—— 把 `healthy=false` 的报告转成异常，
     * [detail][HealthReport.detail]（含友好文案 + SQLState/ErrorCode）即确认框内容。
     */
    override suspend fun testConnection(config: StarRocksConnectionConfig) {
        val report = probe(config)
        if (!report.healthy) throw IllegalStateException(report.detail)
    }

    /** 测试超时文案（与 MQ 系同为 15 秒）。 */
    override fun testTimeoutMessage(): String = stringResource("olap.starrocks.form.test_timeout")

    /** 测试失败前缀（后接报告 detail）。 */
    override fun testFailurePrefix(): String = stringResource("olap.starrocks.form.test_fail_prefix")

    /** 报告 detail 已是友好文案，不再沿 cause 链重复拼接。 */
    override fun describeFailure(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "unknown"

    /** 落盘：写整份 config.toml 走 IO + NonCancellable（与 MQ 系同款）。 */
    override suspend fun persist(config: StarRocksConnectionConfig): Result<Unit> =
        withContext(ioDispatcher + NonCancellable) {
            val info = ConnectionInfo(
                id = config.connectionId,
                name = name.ifBlank { defaultName },
                serviceId = StarRocksConstants.PROTOCOL_ID,
                mqName = config.database,
                host = config.host,
                port = config.port,
                options = config.options,
            )
            if (editingId == null) persistence.create(info) else persistence.update(info)
        }

    /** 落盘失败文案（默认取 message / 类名即可）。 */
    override fun saveFailureMessage(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "unknown"
}
