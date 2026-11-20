package com.mqclient.starrocks

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.ServiceException
import com.mqclient.core.event.EventNames
import com.mqclient.core.event.getString
import com.mqclient.olap.base.perf.olapTimeMillis
import com.mqclient.core.newUuidV7
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.core.spi.onEvent
import com.mqclient.olap.base.connection.HealthReport
import com.mqclient.olap.base.query.ResultBatch
import com.mqclient.starrocks.jdbc.JdbcConnector
import com.mqclient.starrocks.jdbc.MysqlJdbcConnector
import com.mqclient.starrocks.ssh.SshTunnel
import com.mqclient.core.log.LogLevel
import com.mqclient.starrocks.log.StarRocksLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withContext

/**
 * StarRocks service 层的**模块门面**：把 [StarRocksConnectionManager] 暴露给
 * UI 表单，而不让 UI 认识 `ServiceContext` / `java.sql`。
 *
 * 生命周期由 [StarRocksServiceAdapter.initialize] / `dispose` 驱动（宿主
 * `ModuleRegistry.initializeServices` 在启动时注入 [ServiceContext]）。
 * 门面持有注入了真实 [JdbcConnector] 的管理器，按次提供 [testConnection] ——
 * 表单的「测试连接」按钮直接调它，复用 `SELECT 1` +
 * `[OLAP-Perf][connect]` 打点 + finally 关闭逻辑，不重复实现。
 *
 * 未初始化时返回 `healthy=false` 的报告而不是抛异常：表单把它当普通失败展示，
 * 不会让新建连接对话框崩掉。
 */
object StarRocksModule {

    /**
     * 「测试连接」的兜底超时（与 kafka / rabbitmq / rocketmq 的 `TEST_TIMEOUT_MS`
     * 同口径 30s）：JDBC connect 是阻塞 IO，目标端口接受连接却不应答时驱动自己
     * 不会回来，必须有外层闸。
     */
    internal const val TEST_TIMEOUT_MS = 30_000L

    @Volatile
    private var manager: StarRocksConnectionManager? = null

    /** SPI 注入的运行时上下文：连接注册表（连接查找 / 状态上报）与审计事件都经它。 */
    @Volatile
    private var context: ServiceContext? = null

    /** 真实 JDBC 连接器（jvmMain）；UI 侧只通过 [testConnection] 间接使用。 */
    private val connector: JdbcConnector = MysqlJdbcConnector()

    /** 宿主右键菜单动作请求的订阅句柄（`starrocks.connection.*`）；dispose 时取消。 */
    @Volatile
    private var subscriptions: List<Job> = emptyList()

    /**
     * SPI 初始化钩子：由 ServiceAdapter 在宿主启动时调用。
     *
     * 幂等（对齐 `KafkaModule.initialize`）：重复调用直接返回 —— 重入会新建一个
     * manager 顶掉旧的，旧会话与 SSH 隧道就没人关了。
     */
    @Synchronized
    fun initialize(context: ServiceContext) {
        // 已有实例：不重复初始化（订阅也不会重复注册）。
        if (manager != null) return
        this.context = context
        // JVM 侧提供真实的 SSH 隧道实现；commonMain 只认识 ConnectionTunnel 契约。
        manager = StarRocksConnectionManager(
            context,
            connector = connector,
            tunnelFactory = { config -> SshTunnel.open(config) },
        )
        // 宿主的显式动作请求（右键菜单 / 连接页按钮）走事件约定（见 app-core
        // `ConnectionActions`）：**不订阅就等于点了没反应**。此前 StarRocks 只经
        // 面板内的隐式连接工作，菜单里的「连接 / 断开连接 / 测试连接」全部石沉大海。
        // 事件名由 [StarRocksEvents] 从 PROTOCOL_ID 派生，与宿主拼出的
        // `<serviceId>.connection.*` 严格同源。
        subscriptions = listOf(
            context.onEvent(StarRocksEvents.CONNECT_REQUESTED) { event ->
                event.getString("connectionId")?.let { connectById(it) }
            },
            context.onEvent(StarRocksEvents.DISCONNECT_REQUESTED) { event ->
                event.getString("connectionId")?.let { disconnectById(it) }
            },
            context.onEvent(StarRocksEvents.TEST_REQUESTED) { event ->
                event.getString("connectionId")?.let { handleTestRequested(it) }
            },
        )
        StarRocksLog.conn(
            "module initialized (connector=${connector::class.simpleName}, " +
                "menuSubscriptions=${subscriptions.size})",
        )
    }

    /**
     * 退出钩子：**先同步断开共享会话**再摘引用（对齐 KafkaModule.shutdown 的
     * 口径 —— ModuleRegistry.disposeServices 不挂起，退出路径不能留下没关的
     * 物理连接；`runBlocking` 等会话关完才返回）。关闭失败只记日志，不阻断
     * 其余模块的销毁。
     */
    fun dispose() {
        val subscriptionsToCancel = subscriptions
        val managerToStop = manager
        subscriptions = emptyList()
        manager = null
        StarRocksLog.conn("module dispose start (hasSession=${managerToStop != null})")
        try {
            managerToStop?.let { current ->
                runCatching { kotlinx.coroutines.runBlocking { current.shutdown() } }
                    .onFailure {
                        // 退出路径上的失败不会中断用户操作，但会留下物理连接：WARN + 栈。
                        StarRocksLog.conn(
                            LogLevel.WARN,
                            "module dispose: session shutdown failed (ignored): ${it.message}",
                            it,
                        )
                    }
            }
        } finally {
            // 订阅最后收：断开过程中发出的 `olap.connection.closed` 还需要有人在场
            // （与 KafkaModule / IbmMQModule 同序，反了会让在途的 disconnect 被取消，
            // 基类 close() 把取消当「关闭失败」吞掉后清空引用 → 物理连接泄漏）。
            subscriptionsToCancel.forEach(Job::cancel)
            context = null
        }
        StarRocksLog.conn("module disposed")
    }

    /**
     * 一次性连接测试（UI「测试连接」按钮 → 真实探针）。
     * 不复用共享会话：探针自带 connect → SELECT 1 → finally close。
     */
    suspend fun testConnection(config: StarRocksConnectionConfig): HealthReport =
        manager?.testConnection(config) ?: HealthReport(
            healthy = false,
            detail = "StarRocks module is not initialized",
        )

    /**
     * SQL 控制台的查询入口：在当前共享会话上执行 [sql]，按批流式返回。
     *
     * ## 执行历史落库
     *
     * [onCompletion] 是唯一的执行咽喉点：流正常收完（SUCCESS）或异常结束
     * （FAILED，含 SQLException / 取消）都会回调，在此计算耗时并组装**自包含
     * payload** 广播 [EventNames.OLAP_QUERY_EXECUTED]。**只记 SQL 原文 + 状态 +
     * 耗时 + 错误信息，绝不记结果集**（见 olap_query.sq 表头）—— 10 万行的结果
     * 落盘会把本地库撑爆。
     *
     * 与 IBM MQ 的 mq_command 审计完全同款「事件自包含 + 咽喉点组装」：落库统一
     * 在核内 handler（OlapQueryHistoryHandler），模块不再直取 repository /
     * `CoreModule.scope` 单例 —— emit 不挂起、不阻塞 Flow 收集，写盘失败走核内
     * 错误通道，绝不反噬查询结果。
     */
    fun executeQuery(connectionId: String, sql: String): Flow<ResultBatch> {
        val current = manager ?: return flow {
            // 功能级中断：模块没初始化就不可能有查询，走 ERROR 带栈（便于发现初始化顺序问题）。
            val error = IllegalStateException("StarRocks module is not initialized")
            StarRocksLog.sql(LogLevel.ERROR, "exec rejected connection=$connectionId (module not initialized)", error)
            throw error
        }
        val startedAt = olapTimeMillis()
        val recordId = newUuidV7()
        return flow {
            // 身份守卫 + 懒建连下沉：会话不属于该连接时自动抢占 / 补连（单槽语义，
            // 抢占会断开旧连接的会话；UI 侧的 isAlive 预检只是加速路径，不再是
            // 正确性依赖）。连接查不到 / 建连失败原样抛给收集方。
            if (!current.isAlive(connectionId)) {
                current.connect(findConfig(connectionId)).getOrThrow()
            }
            // 单槽串行由 manager.executeQuery 内的 queryMutex 保证（此处只做
            // 身份守卫 + 懒建连，守卫不取锁、connect 自带 manager 级互斥）。
            current.executeQuery(sql, connectionId).collect { batch -> emit(batch) }
        }.onCompletion { cause ->
            val recordContext = context ?: return@onCompletion  // 未初始化（无界面脚本）时静默跳过
            recordContext.emit(
                name = EventNames.OLAP_QUERY_EXECUTED,
                payload = mapOf(
                    "recordId" to recordId,
                    "connectionId" to connectionId,
                    "sqlText" to sql,
                    "status" to if (cause == null) "SUCCESS" else "FAILED",
                    "durationMs" to (olapTimeMillis() - startedAt),
                    "errorMessage" to cause?.let {
                        if (it is CancellationException) "cancelled" else (it.message ?: it::class.simpleName)
                    },
                    "createdAt" to startedAt,
                ),
            )
        }
    }

    /* ------------------------------ 懒加载连接 ------------------------------ */

    /**
     * 共享会话是否存活**且属于 [connectionId]**（SQL 控制台「执行」前的预检）。
     * 会话属于别的连接时返回 false —— 调用方 connectById / executeQuery 会自动
     * 抢占（单槽语义，见 [AbstractAnalyticsConnectionManager.connect]）。
     */
    fun isAlive(connectionId: String): Boolean = manager?.isAlive(connectionId) == true

    /**
     * 按连接 id 还原配置（找不到返回 null）。
     *
     * 供需要「连接凭据本身」的功能使用 —— 目前是 FE 日志：它走 Web 端口（8030）的
     * Basic Auth，与 MySQL 会话不是同一条通道，必须能拿到 host / 端口 / 用户名。
     * 密码只在进程内存里流转，不落日志（见 StarRocksLog.redactJdbcUrl 的口径）。
     */
    fun configOrNull(connectionId: String): StarRocksConnectionConfig? = runCatching {
        findConfig(connectionId)
    }.getOrNull()

    /** 按连接 id 从注入的注册表还原配置（找不到连接抛 [ServiceException]）。 */
    private fun findConfig(connectionId: String): StarRocksConnectionConfig {
        val info: ConnectionInfo = context?.connectionRegistry?.findConnection(connectionId)
            ?: throw ServiceException(
                errorKey = "err_conn_not_found",
                args = listOf(connectionId),
                fallbackMessage = "Connection '$connectionId' not found",
            )
        return StarRocksConnectionConfig.fromOptions(info.id, info.name, info.options)
    }

    /**
     * 按**落盘连接**建立会话（SQL 控制台「点击执行自动连接」的入口）。
     *
     * 从注入的 [ServiceContext.connectionRegistry] 按 [connectionId] 找 [ConnectionInfo]，options
     * 还原成 [StarRocksConnectionConfig] 后交 [StarRocksConnectionManager.connect]
     * —— 基类对「已有可用会话」幂等返回，**自动建好的 Session 保存在 manager 里**，
     * 后续执行直接复用（单槽语义，与右侧树上手动连接同一份会话）。
     *
     * 失败以 `Result.failure` 返回（找不到连接 → [ServiceException]；建连失败 →
     * 底层 `JdbcFailure` 等），UI 用 `userMessage()` 翻译后展示。
     *
     * ## 连接状态同步（左侧树变绿的关键）
     *
     * 项目约定：连接状态**不走 EventBus**（状态用 StateFlow，见 EventNames），左侧树
     * 订阅的是注册表的 `connections` 这份 StateFlow（`collectAsState`）。而
     * `AbstractAnalyticsConnectionManager.connect` 只发了
     * `olap.connection.connected` 事件、**没有** reportStatus —— 正是「执行触发了
     * 底层连接，树却仍显示未连接」的断点。这里按 MQ 模块同款（mq-base
     * `setStatus` / IbmMQService 心跳）在 connect 成功 / 失败时上报事实状态，
     * `connectionId` 直接取树节点传下来的 id，与快照里的 `ConnectionInfo.id`
     * 严格一致。
     */
    suspend fun connectById(connectionId: String): Result<Unit> {
        val current = manager ?: return Result.failure(
            IllegalStateException("StarRocks module is not initialized"),
        )
        val config = try {
            findConfig(connectionId)
        } catch (error: ServiceException) {
            return Result.failure(error)
        }
        // 抢占前记下旧会话归属：connect 成功且换了连接时，旧连接的树状态要退下。
        val previousId = current.currentConnectionId()
        val result = current.connect(config).map { }
        if (result.isSuccess) {
            StarRocksLog.conn("connectById ok connectionId=$connectionId previous=$previousId")
            if (previousId != null && previousId != connectionId) {
                reportStatus(previousId, ConnectionStatus.Disconnected)
            }
            reportStatus(connectionId, ConnectionStatus.Connected)
        } else {
            // 用户点了连接却失败：WARN（业务异常），底层原因由 manager.openSession 的 ERROR 记录。
            StarRocksLog.conn(
                LogLevel.WARN,
                "connectById failed connectionId=$connectionId: ${result.exceptionOrNull()?.message}",
            )
            reportStatus(connectionId, ConnectionStatus.Failed)
        }
        return result
    }

    /**
     * 按连接 id 断开（宿主右键菜单「断开连接」的落点）。
     *
     * 单槽管理器一次只服务一条连接：只有当前会话确实属于 [connectionId] 时才真的
     * 关物理连接；属于别的连接（例如树上的状态已陈旧）时不碰那条会话，只把本连接
     * 的事实状态复位成 [ConnectionStatus.Disconnected]。状态必须上报 —— 宿主靠
     * `ConnectionStore` 的 Disconnected 跳变收掉该连接的 Tab（否则面板的隐式连接会
     * 立刻把用户刚点的「断开」撤销掉）。
     */
    suspend fun disconnectById(connectionId: String) {
        val current = manager ?: return
        // 判据 + 关闭在同一把 manager 锁内完成：先读 currentConnectionId() 再 close()
        // 会让出窗口给别的连接抢占槽位，从而关掉别人刚建好的会话。
        val closed = current.closeIfOwnedBy(connectionId)
        if (!closed) {
            StarRocksLog.conn(
                "disconnect: no session to close connectionId=$connectionId " +
                    "owner=${current.currentConnectionId()}",
            )
        }
        reportStatus(connectionId, ConnectionStatus.Disconnected)
    }

    /**
     * 「测试连接」请求的处理：按连接 id 还原配置，发起一次**临时探针**（用完即关，
     * 不影响共享会话与状态），结果一律发 [StarRocksEvents.TEST_COMPLETED] ——
     * 成功失败都发，宿主据此弹结果对话框。连接不存在 / 模块未初始化按失败上报。
     */
    private suspend fun handleTestRequested(connectionId: String) {
        val config = runCatching { findConfig(connectionId) }.getOrNull()
        if (config == null) {
            emitTestCompleted(
                connectionId = connectionId,
                success = false,
                message = "Connection '$connectionId' not found",
                durationMs = 0L,
            )
            return
        }
        val startedAt = olapTimeMillis()
        // 兜底超时（与 kafka / rabbitmq / rocketmq 同口径）：探针是阻塞 IO，目标端口
        // 「接受但不应答」时 DriverManager 不会自己回来。没有这道闸，宿主永远收不到
        // TEST_COMPLETED —— 用户看到的还是「点了没反应」，而且事件处理器是串行的，
        // 之后每一次「测试连接」都会被一起堵住。
        val report = try {
            withTimeout(TEST_TIMEOUT_MS) {
                manager?.testConnection(config) ?: HealthReport(
                    healthy = false,
                    detail = "StarRocks module is not initialized",
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            HealthReport(
                healthy = false,
                detail = "test timed out after ${TEST_TIMEOUT_MS}ms (${config.host}:${config.port})",
            )
        }
        val durationMs = olapTimeMillis() - startedAt
        emitTestCompleted(
            connectionId = connectionId,
            success = report.healthy,
            message = report.detail,
            durationMs = durationMs,
        )
        StarRocksLog.conn(
            "test completed connectionId=$connectionId success=${report.healthy} cost=${durationMs}ms",
        )
    }

    /** 测试结果事件（宿主 `.connection.test.completed` 后缀订阅据此弹框）。 */
    private fun emitTestCompleted(
        connectionId: String,
        success: Boolean,
        message: String,
        durationMs: Long,
    ) {
        context?.emit(
            name = StarRocksEvents.TEST_COMPLETED,
            payload = mapOf(
                "connectionId" to connectionId,
                "success" to success,
                "message" to message,
                "durationMs" to durationMs,
            ),
        )
    }

    /**
     * 上报连接状态：优先切 [Dispatchers.Main]（UI 事实在主线程刷新），无 Main
     * dispatcher 的无界面宿主回退当前线程 —— `reportStatus` 本身是
     * `@Synchronized` 的 StateFlow 更新，线程安全，`collectAsState` 在组合线程
     * 消费，不会因线程归属丢事件。
     */
    private suspend fun reportStatus(connectionId: String, status: ConnectionStatus) {
        val registry = context?.connectionRegistry ?: return
        val onMain = runCatching {
            withContext(Dispatchers.Main) {
                registry.reportStatus(connectionId, status, StarRocksConstants.PROTOCOL_ID)
            }
        }
        if (onMain.isFailure) {
            registry.reportStatus(connectionId, status, StarRocksConstants.PROTOCOL_ID)
        }
    }
}
