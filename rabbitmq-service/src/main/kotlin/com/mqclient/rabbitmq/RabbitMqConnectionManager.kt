package com.mqclient.rabbitmq

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.scheduler.ScheduledJob
import com.mqclient.core.spi.ServiceContext
import com.mqclient.core.spi.emit
import com.mqclient.mq.base.AbstractConnectionManager
import com.rabbitmq.client.Connection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RabbitMQ 连接的生命周期管理：**显式连接模型**的中枢。
 *
 * 生命周期模板（状态机 / 每连接锁 / 事件 / 扫描任务 / 连续失败阈值与自愈）全部在
 * [AbstractConnectionManager]，本类只提供 RabbitMQ 特有部分：
 *
 *  - [open]：建管理 API 客户端 + `GET /api/overview` 验证（顺带拿集群元信息）；
 *  - [closeResources]：关管理 API 客户端 + 共享 AMQP 连接（后者带 3s 超时，失败 abort 兜底）；
 *  - [amqpConnection]：**懒建**共享 AMQP 连接（消息浏览 / 发送共用），走 [withConnectionLock]。
 *
 * 与 Kafka 的差异：没有惰性句柄 —— HttpClient 建立本身不连网，「验证连通」就是第一次
 * 真实请求（overview）。`connect()` 不建 AMQP 连接：只想看队列列表的用户不该为一次
 * AMQP 握手买单，也不该承担 AMQP 失败风险。
 */
class RabbitMqConnectionManager(
    context: ServiceContext,
    private val service: RabbitMqScanService,
) : AbstractConnectionManager<RabbitMqConnectionContext>(context, MODULE_ID) {

    /* ------------------------------ 基类钩子 ------------------------------ */

    override fun createContext(connectionId: String, connection: ConnectionInfo): RabbitMqConnectionContext =
        RabbitMqConnectionContext(connectionId, rabbitMqConfigFromOptions(connection.options))

    override fun applyConfig(ctx: RabbitMqConnectionContext, connection: ConnectionInfo) {
        ctx.config = rabbitMqConfigFromOptions(connection.options)
    }

    override fun describe(ctx: RabbitMqConnectionContext): String =
        "host=${ctx.config.host}:${ctx.config.managementPort} vhost=${ctx.config.vhost}"

    override suspend fun open(ctx: RabbitMqConnectionContext) {
        val (admin, clusterInfo) = createVerifiedAdmin(ctx.config)
        ctx.admin = admin
        ctx.pendingClusterInfo = clusterInfo
    }

    /**
     * 集群元信息（clusterName / 版本 / 节点）随建连验证顺带入缓存 —— 概览页顶部
     * 从这里读，不再单独发请求。永不过期（TTL 0）：生命周期由整个缓存托管（断开即清）。
     *
     * 放在 [onConnected] 而不是 [open]：基类在 open 之后才创建 cache。
     */
    override suspend fun onConnected(ctx: RabbitMqConnectionContext) {
        val info = ctx.pendingClusterInfo ?: return
        ctx.pendingClusterInfo = null
        ctx.cache?.put(KEY_CLUSTER_INFO, info, ttlMs = 0L)
    }

    override suspend fun onBeforeDisconnect(ctx: RabbitMqConnectionContext) {
        service.cancelScan(ctx.connectionId)
        RabbitMqModule.clearScanFailures(ctx.connectionId)
    }

    override fun isHandlePresent(ctx: RabbitMqConnectionContext): Boolean = ctx.admin != null

    override val connectedEvent: String = RabbitMqEvents.CONNECTED
    override val connectFailedEvent: String = RabbitMqEvents.CONNECT_FAILED
    override val disconnectedEvent: String = RabbitMqEvents.DISCONNECTED

    /**
     * 状态快扫（runOnStart）+ 对象慢扫（晚 5 秒起步，把首拍让给快扫）。
     * 间隔来自用户建连接时的配置，**不硬编码**。
     */
    override fun scanJobs(ctx: RabbitMqConnectionContext): List<ScheduledJob> {
        val config = ctx.config
        return listOf(
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, ctx.connectionId, JOB_SCAN_STATUS),
                intervalMs = config.statusScanIntervalSeconds * 1_000L,
                runOnStart = true,
                action = { service.scanStatus(ctx.connectionId) },
            ),
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, ctx.connectionId, JOB_SCAN_OBJECTS),
                intervalMs = config.objectScanIntervalMinutes * 60_000L,
                initialDelayMs = OBJECTS_FIRST_DELAY_MS,
                action = { service.scanObjects(ctx.connectionId) },
            ),
        )
    }

    /* ------------------------------ 模块特有 ------------------------------ */

    /**
     * 「测试连接」：用给定配置发起一次**临时连接**（overview 验证后立刻关掉，不影响
     * 任何正式连接的状态），失败抛异常 —— 成功返回即连通。
     */
    suspend fun testConnection(config: RabbitMqConfig) {
        val (admin, _) = createVerifiedAdmin(config)
        try {
            admin.close()
        } catch (error: Throwable) {
            log.warn("test connection close failed: error=$error")
        }
    }

    /**
     * 取（必要时懒建）本连接的**共享 AMQP 连接** —— 消息浏览 / 发送共用它。
     *
     * 设计要点：
     *
     *  1. **懒建**：[connect] 不建它（理由见类 KDoc）。
     *  2. **判死整条连接**（产品决定）：AMQP 建不起来时把连接置 `Failed` 并发
     *     `CONNECT_FAILED`。管理 API 能连但 AMQP 连不上，通常意味着端口 / 证书 /
     *     权限配置里有一项是错的。
     *  3. **双检锁**：两个对话框同时首开只建一条物理连接。
     *  4. 超时全部由 [rabbitMqConnectionFactory] 提供（握手 10s、shutdown 3s、
     *     channel RPC 5s），这里不再叠协程层 `withTimeout`。
     *  5. **死连接必须关掉再覆盖**（见 `stale`）：`ctx.amqp` 可能指向一条 `isOpen == false`
     *     的连接。直接覆盖会让旧连接**永远失去引用** —— socket、心跳线程、SSL 上下文
     *     再也没人能释放，反复重连就是稳定泄漏。
     *
     * 抛出的异常已转成人类可读文案（[mapAmqpOpenError]），由调用方转「加载失败」。
     */
    suspend fun amqpConnection(ctx: RabbitMqConnectionContext): Connection {
        ctx.amqp?.takeIf { it.isOpen }?.let { return it }
        return withConnectionLock(ctx.connectionId) {
            // 双检：排队期间可能已被另一个调用方建好。
            ctx.amqp?.takeIf { it.isOpen }?.let { return@withConnectionLock it }

            // 走到这里说明旧连接不存在或已死。先取出来：新连接建好之后要负责把它关掉。
            val stale = ctx.amqp

            try {
                val created = withContext(Dispatchers.IO) {
                    rabbitMqConnectionFactory(ctx.config).newConnection()
                }
                // 先赋值再回收旧连接：新连接已经接上了，期间若回收慢也不影响可用性。
                ctx.amqp = created

                if (stale != null) {
                    // **只关已死的**。isOpen 为真的连接说明它还在为我们工作，
                    // 关掉会把正在读消息的对话框一起断掉。
                    if (!stale.isOpen) {
                        log.info("disposing stale amqp connection before replacing: connection=${ctx.connectionId}")
                        runCatching { withContext(Dispatchers.IO) { stale.close(AMQP_SHUTDOWN_TIMEOUT_MS) } }
                            .onFailure { e ->
                                // close 失败要 abort 兜底：否则 socket 会留在半开 / TIME_WAIT。
                                runCatching { stale.abort() }
                                log.warn(
                                    "stale amqp connection close failed (aborted): " +
                                        "connection=${ctx.connectionId} error=$e",
                                )
                            }
                    } else {
                        log.debug("skipping stale connection disposal (still open): connection=${ctx.connectionId}")
                    }
                }

                log.debug(
                    "amqp connection opened (lazy): connection=${ctx.connectionId} " +
                        "host=${ctx.config.host}:${ctx.config.amqpPort}",
                )
                created
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // 产品决定：AMQP 不可用 = 这条连接不可用。
                val reason = errorMessage(mapAmqpOpenError(error, ctx.config))
                ctx.lastError = reason
                setStatus(ctx, ConnectionStatus.Failed)
                context.emit(
                    name = RabbitMqEvents.CONNECT_FAILED,
                    payload = mapOf("connectionId" to ctx.connectionId, "message" to reason),
                )
                log.warn("amqp connection failed: connection=${ctx.connectionId} reason=$reason")
                throw mapAmqpOpenError(error, ctx.config)
            }
        }
    }

    /* ------------------------------ 内部 ------------------------------ */

    /**
     * 建管理 API 客户端并用 `GET /api/overview` 验证连通性（认证 + 地址 + vhost 一次
     * 验完）。失败（含取消）时自己把刚建的客户端关掉再抛。
     */
    private suspend fun createVerifiedAdmin(
        config: RabbitMqConfig,
    ): Pair<RabbitMqAdminClient, RabbitMqClusterInfoSnapshot> {
        var client: RabbitMqAdminClient? = null
        try {
            client = withContext(Dispatchers.IO) { RabbitMqAdminClient(config) }
            val overview = withContext(Dispatchers.IO) { client.overview() }
            val info = RabbitMqClusterInfoSnapshot(
                clusterName = overview.clusterName,
                rabbitmqVersion = overview.rabbitmqVersion,
                node = overview.node,
            )
            log.info(
                "management api verified: cluster=${info.clusterName} " +
                    "rabbitmq=${info.rabbitmqVersion} node=${info.node}",
            )
            return client to info
        } catch (error: Throwable) {
            runCatching { client?.close() }
            throw error
        }
    }

    /**
     * 关掉本连接的两类句柄：管理 API 客户端与共享 AMQP 连接。
     *
     * AMQP 用 `close(AMQP_SHUTDOWN_TIMEOUT_MS)`（≤3s）而不是无参 `close()`：后者等
     * close-ok 的默认上限是 **10 秒**，broker 不应答时断开路径会卡住整整 10s ——
     * 这正是「点关闭没反应」的形态之一。
     */
    override suspend fun closeResources(ctx: RabbitMqConnectionContext) {
        val admin = ctx.admin
        val amqp = ctx.amqp
        ctx.admin = null
        ctx.amqp = null
        if (admin != null) {
            try {
                withContext(Dispatchers.IO) { admin.close() }
                log.debug("management api client closed: connection=${ctx.connectionId}")
            } catch (error: Throwable) {
                log.warn("management api client close failed: connection=${ctx.connectionId} error=$error")
            }
        }
        if (amqp != null) {
            try {
                withContext(Dispatchers.IO) { amqp.close(AMQP_SHUTDOWN_TIMEOUT_MS) }
                log.debug("amqp connection closed: connection=${ctx.connectionId}")
            } catch (error: Throwable) {
                // close 失败要 abort 兜底：否则底层 socket 会留在 TIME_WAIT / 半开状态。
                runCatching { amqp.abort() }
                log.warn("amqp connection close failed (aborted): connection=${ctx.connectionId} error=$error")
            }
        }
    }
}
