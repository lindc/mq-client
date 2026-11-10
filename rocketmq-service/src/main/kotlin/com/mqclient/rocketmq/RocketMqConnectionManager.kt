package com.mqclient.rocketmq

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.scheduler.ScheduledJob
import com.mqclient.core.spi.ServiceContext
import com.mqclient.mq.base.AbstractConnectionManager
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RocketMQ 连接的生命周期管理：**显式连接模型**的中枢。
 *
 * 生命周期模板（状态机 / 每连接锁 / 事件 / 扫描任务 / 失败阈值）全部在
 * [AbstractConnectionManager]，本类只提供 RocketMQ 特有三件事：
 *
 *  - [open]：建 `DefaultMQAdminExt` + `startAndVerify`（start 会真的连 NameServer，
 *    验证靠 examineBrokerClusterInfo；超时由构造参数兜底）；
 *  - [closeResources]：关 AdminExt + 共享的 PullConsumer / Producer（后者要先
 *    `take()` 出句柄槽，孤儿会让 TLS 临时 PEM —— 含未加密私钥 —— 永久留在临时目录）；
 *  - [onConnected]：复位句柄槽（必须在置 Connected 之前，见基类 [connect] 的顺序说明）。
 *
 * 扫描任务两条：Lag 快扫（`statusScanIntervalSeconds`，runOnStart）+ 对象慢扫
 * （`objectScanIntervalMinutes`，晚 5 秒起步把首拍让给快扫）。
 */
class RocketMqConnectionManager(
    context: ServiceContext,
    private val service: RocketMqScanService,
) : AbstractConnectionManager<RocketMqConnectionContext>(context, MODULE_ID) {

    /* ------------------------------ 基类钩子 ------------------------------ */

    override fun createContext(connectionId: String, connection: ConnectionInfo): RocketMqConnectionContext =
        RocketMqConnectionContext(connectionId, rocketMqConfigFromOptions(connection.options))

    override fun applyConfig(ctx: RocketMqConnectionContext, connection: ConnectionInfo) {
        ctx.config = rocketMqConfigFromOptions(connection.options)
    }

    override fun describe(ctx: RocketMqConnectionContext): String = "namesrv=${ctx.config.namesrvAddr}"

    override suspend fun open(ctx: RocketMqConnectionContext) {
        ctx.adminClient = createVerifiedAdmin(ctx.config)
    }

    override suspend fun onConnected(ctx: RocketMqConnectionContext) {
        // 复位句柄槽 —— 必须在 setStatus(Connected) 之前（由基类保证调用时机）。
        ctx.messageClients.reopen()
    }

    override suspend fun onBeforeDisconnect(ctx: RocketMqConnectionContext) {
        // 手动刷新（UI 按钮）的协程也要收：它占着该连接的扫描锁，句柄又要关了。
        service.cancelScan(ctx.connectionId)
        // 扫描完成标记与缓存同生命周期：断开清掉，重连后重新等首扫。
        service.clearScanState(ctx.connectionId)
        // 「部分数据未刷新」的旧结论不能诈尸到重连后（新连接可能权限不同）。
        RocketMqModule.clearScanFailures(ctx.connectionId)
    }

    override fun isHandlePresent(ctx: RocketMqConnectionContext): Boolean = ctx.adminClient != null

    /** 解掉 `ExecutionException` 包装（RocketMQ 的 future 路径会包一层）。 */
    override fun errorMessage(error: Throwable): String =
        (error as? ExecutionException)?.cause?.message ?: super.errorMessage(error)

    override val connectedEvent: String = RocketMqEvents.CONNECTED
    override val connectFailedEvent: String = RocketMqEvents.CONNECT_FAILED
    override val disconnectedEvent: String = RocketMqEvents.DISCONNECTED

    /**
     * 按配置注册两条扫描任务（与 kafka 同构）：快扫 runOnStart、慢扫晚 5 秒起步。
     *
     * 幂等：同 ID 重复注册会顶掉旧的（见 JobScheduler），重连时直接重注册即可。
     */
    override fun scanJobs(ctx: RocketMqConnectionContext): List<ScheduledJob> {
        val config = ctx.config
        return listOf(
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, ctx.connectionId, JOB_SCAN_LAG),
                intervalMs = config.statusScanIntervalSeconds * 1_000L,
                runOnStart = true,
                action = { service.scanGroupLags(ctx.connectionId) },
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

    /** 「测试连接」：临时连接（start + 验证后立刻关掉），失败抛异常。 */
    suspend fun testConnection(config: RocketMqConfig) {
        val admin = createVerifiedAdmin(config)
        try {
            withContext(Dispatchers.IO) { admin.close() }
        } catch (error: Throwable) {
            log.warn("test connection close failed: error=$error")
        }
    }

    /**
     * 建 AdminExt 并用 examineBrokerClusterInfo 验证连通性。失败（含取消）时自己把
     * 刚建的句柄关掉再抛，成功时把可用的 AdminClient 交给调用方。
     */
    private suspend fun createVerifiedAdmin(config: RocketMqConfig): RocketMqAdminClient {
        val client = RocketMqAdminClient(config)
        try {
            client.startAndVerify()
            return client
        } catch (error: Throwable) {
            runCatching { client.close() }
            throw error
        }
    }

    /**
     * 关 AdminExt + 共享消息客户端。切到 IO 线程；失败只记日志 —— 断开路径不能被
     * 一个关不掉的句柄中断。
     */
    override suspend fun closeResources(ctx: RocketMqConnectionContext) {
        val admin = ctx.adminClient
        ctx.adminClient = null
        // 「mark disposed + 取走引用」在同一监视器里完成，然后才在锁外 shutdown。
        // 孤儿一旦消失，句柄持有的 TLS 临时 PEM（含未加密私钥）才一定会被 close() 删。
        val clients = ctx.messageClients.take()
        if (admin != null) {
            try {
                withContext(Dispatchers.IO) { admin.close() }
                log.debug("admin client closed: connection=${ctx.connectionId}")
            } catch (error: Throwable) {
                log.warn("admin client close failed: connection=${ctx.connectionId} error=$error")
            }
        }
        // 共享 PullConsumer / Producer：不等待 access 锁（等锁等于把断开路径一起拖死）。
        if (clients != null) {
            try {
                clients.shutdown()
                log.debug("shared message clients shut down: connection=${ctx.connectionId}")
            } catch (error: Throwable) {
                log.warn("shared message clients shutdown failed: connection=${ctx.connectionId} error=$error")
            }
        }
    }

    /** 供扫描服务判定「连不连得上」时复用（保持原有调用面）。 */
}
