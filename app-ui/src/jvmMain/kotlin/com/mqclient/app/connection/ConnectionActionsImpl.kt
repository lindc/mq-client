package com.mqclient.app.connection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ConnectionActions
import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ConnectionStore
import com.mqclient.core.CoreModule
import com.mqclient.core.event.getString
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.core.newConnectionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 一次「测试连接」的结果（来源：`<serviceId>.connection.test.completed` 事件）。 */
data class TestResultData(
    val connectionName: String,
    val success: Boolean,
    val message: String,
    val durationMs: Long,
)

/** 「查看属性先连接」流程里的连接失败（message 可能为 null —— 超时 / 模块没发失败事件）。 */
data class ConnectFailure(val connectionId: String, val connectionName: String, val message: String?)

/**
 * 右键菜单各对话框的 UI 状态。对话框都是 MainWindow 级的一次性 UI，用 Compose
 * 状态而不是事件寻址（与 [com.mqclient.core.CoreModule.lastError] 同一哲学：
 * 对话框要显示的是「当前有没有」，事件表达不了这个）。
 */
class ConnectionMenuState {
    /** 待二次确认的删除目标；null = 无。 */
    var deleteTarget by mutableStateOf<ConnectionInfo?>(null)

    /**
     * 待编辑的连接目标；null = 无。点击「修改连接」后把目标连接记到这里，由
     * `ConnectionMenuDialogs` 渲染 `EditConnectionDialog`（预填 + 保存走更新）。
     */
    var editTarget by mutableStateOf<ConnectionInfo?>(null)

    /** 待打开的属性对话框目标（已连接，内容由模块的 ConnectionPropertiesUi 贡献）。 */
    var propertiesTarget by mutableStateOf<ConnectionInfo?>(null)

    /** 「查看属性先连接」进行中的连接（显示「正在连接」提示）。 */
    var connectingForProperties by mutableStateOf<ConnectionInfo?>(null)

    /** 先连接流程的失败信息（弹错误对话框，带重试）。 */
    var connectFailure by mutableStateOf<ConnectFailure?>(null)

    /**
     * 一次**普通显式连接**的失败（右键菜单 / 模块面板的连接按钮，来源：
     * `<serviceId>.connection.failed` 事件）。与 [connectFailure] 分开持有：
     * 两者的重试语义不同 —— 前者重试整个「先连接再查属性」编排，后者只重试连接。
     */
    var connectError by mutableStateOf<ConnectFailure?>(null)

    /** 最近一次「测试连接」的结果（结果对话框）。 */
    var testResult by mutableStateOf<TestResultData?>(null)
}

/**
 * [ConnectionActions] 的宿主实现。动作分三类：
 *
 *  - **跨模块动作**（connect / disconnect / test）：发 `<serviceId>.connection.*`
 *    事件，由各模块订阅执行（事件约定见 app-core `ConnectionActions` 的文档）；
 *  - **纯核心动作**（copy / delete）：直接操作 [ConnectionStore] 或置对话框状态；
 *  - **编排动作**（showQmgrProperties）：先连接再开框 —— 发连接事件后订阅
 *    [ConnectionStore.connections] 等状态到终态（Connected / Failed），期间显示
 *    「正在连接」。等结果用状态流而不是事件：状态是事实来源（连接中的状态跳变、
 *    模块初始化失败等异常路径都能收敛到终态或超时），事件可能没人发。
 */
class ConnectionActionsImpl(
    private val state: ConnectionMenuState,
    /**
     * 「复制连接」落盘用的作用域（宿主传入）。
     *
     * 不能用 `rememberCoroutineScope()` 的即时作用域：复制是 fire-and-forget 的落盘动作，
     * 不该因为右键菜单关闭而被取消；也不该占菜单自己的生命周期。
     */
    private val scope: CoroutineScope,
) : ConnectionActions {

    override suspend fun connect(connectionId: String) = request(connectionId, "connect")

    override suspend fun disconnect(connectionId: String) = request(connectionId, "disconnect")

    override fun edit(connectionId: String) {
        // 点击「修改连接」：记录目标连接，由 ConnectionMenuDialogs 打开 EditConnectionDialog。
        // 预填（loadFrom）与保存（submit → ConnectionStore.update）都在对话框 / 表单层完成，
        // 这里只负责把目标交给渲染层。
        state.editTarget = connectionById(connectionId)
    }

    override fun copy(connectionId: String) {
        val connection = connectionById(connectionId) ?: return
        val duplicate = connection.copy(
            id = newConnectionId(),
            name = connection.name + stringResource("connection_copy_suffix"),
            status = ConnectionStatus.Disconnected,
        )
        // 落盘要写整份 config.toml（含敏感字段 AES 加密 + 原子换文件），不能压在
        // 点击回调所在的 UI 线程上。失败仍然走错误通道，与之前一致。
        scope.launch {
            val result = withContext(Dispatchers.IO) { ConnectionStore.create(duplicate) }
            result.onFailure { failure ->
                CoreModule.reportError(
                    message = failure.message ?: failure::class.simpleName ?: "unknown",
                    source = "app-ui",
                    error = failure,
                )
            }
        }
    }

    override fun delete(connectionId: String) {
        state.deleteTarget = connectionById(connectionId)
    }

    override suspend fun test(connectionId: String) = request(connectionId, "test")

    @OptIn(ExperimentalCoroutinesApi::class) // CompletableDeferred.getCompleted
    override suspend fun showQmgrProperties(connectionId: String) {
        val connection = connectionById(connectionId) ?: return
        when (connection.status) {
            // 连接中：忽略重复触发（约束 7）。
            ConnectionStatus.Connecting -> return
            ConnectionStatus.Connected -> {
                state.propertiesTarget = connection
                return
            }
            else -> Unit
        }

        // 未连接 / 失败：先连接。连接请求是异步事件，这里等状态到终态。
        val bus = CoreModule.eventBus
        state.connectingForProperties = connection
        coroutineScope {
            // 失败详情走 `.connection.failed` 事件（如 ibm-mq 的 CONNECT_FAILED）；
            // 它与状态流并行等，谁也不阻塞谁 —— 状态是是否成功的权威，事件只补消息。
            val failureMessage = CompletableDeferred<String>()
            val listener = bus.onSuffix(".connection.failed", this) { event ->
                if (event.getString("connectionId") == connectionId) {
                    event.getString("message")?.let { failureMessage.complete(it) }
                }
            }
            bus.emit(
                name = "${connection.serviceId}.connection.connect",
                payload = mapOf("connectionId" to connectionId),
                source = SOURCE,
            )
            val finalStatus = withTimeoutOrNull(CONNECT_WAIT_TIMEOUT_MS) {
                ConnectionStore.connections
                    .map { list -> list.firstOrNull { it.id == connectionId }?.status }
                    .filter { it == ConnectionStatus.Connected || it == ConnectionStatus.Failed }
                    .first()
            }
            listener.cancel()
            state.connectingForProperties = null
            when (finalStatus) {
                ConnectionStatus.Connected -> state.propertiesTarget = connection
                ConnectionStatus.Failed -> state.connectFailure = ConnectFailure(
                    connectionId = connectionId,
                    connectionName = connection.name,
                    // getCompleted 取「已到达的失败详情」；没到（取消 / 时序）就 null。
                    message = runCatching { failureMessage.getCompleted() }.getOrNull(),
                )
                // 超时：连接请求石沉大海（模块未初始化 / 底层 IO 挂死）。
                else -> state.connectFailure = ConnectFailure(connectionId, connection.name, null)
            }
        }
    }

    /** 跨模块动作的统一出口：`<serviceId>.connection.<action>` + `connectionId`。 */
    private suspend fun request(connectionId: String, action: String) {
        val connection = connectionById(connectionId) ?: return
        CoreModule.eventBus.emit(
            name = "${connection.serviceId}.connection.$action",
            payload = mapOf("connectionId" to connectionId),
            source = SOURCE,
        )
    }

    private fun connectionById(connectionId: String): ConnectionInfo? =
        ConnectionStore.connections.value.firstOrNull { it.id == connectionId }

    private companion object {
        /** 事件的 source：宿主 UI 发出的动作，与模块 / core 区分。 */
        const val SOURCE = "app-ui"

        /** 等待「先连接」完成的兜底超时：MQ 的 TCP 连接超时可达 2 分钟，这里放宽到 2 分钟。 */
        const val CONNECT_WAIT_TIMEOUT_MS = 120_000L
    }
}
