package com.mqclient.kafka

import com.mqclient.core.ConnectionInfo
import com.mqclient.core.scheduler.ScheduledJob
import com.mqclient.core.spi.ServiceContext
import com.mqclient.mq.base.AbstractConnectionManager
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.DescribeClusterOptions

/**
 * Kafka 连接的生命周期管理：**显式连接模型**的中枢。
 *
 * 生命周期模板（状态机 / 每连接锁 / 事件 / 扫描任务 / 连续失败阈值与自愈）全部在
 * [AbstractConnectionManager]，本类只提供 Kafka 特有部分：
 *
 *  - [open]：`AdminClient.create` + `describeCluster` 验证（create 是惰性的，真正的
 *    TCP 连接发生在第一次 API 调用 —— 所以验证必须发一次请求，顺带把 broker 列表拿到手）；
 *  - [closeResources]：关 AdminClient + 共享 consumer / producer（先 wakeup 打断 poll）；
 *  - [onConnected]：复位句柄槽（必须在置 Connected 之前）。
 */
class KafkaConnectionManager(
    context: ServiceContext,
    private val service: KafkaScanService,
) : AbstractConnectionManager<KafkaConnectionContext>(context, MODULE_ID) {

    /* ------------------------------ 基类钩子 ------------------------------ */

    override fun createContext(connectionId: String, connection: ConnectionInfo): KafkaConnectionContext =
        KafkaConnectionContext(connectionId, kafkaConfigFromOptions(connection.options))

    override fun applyConfig(ctx: KafkaConnectionContext, connection: ConnectionInfo) {
        ctx.config = kafkaConfigFromOptions(connection.options)
    }

    override fun describe(ctx: KafkaConnectionContext): String = "bootstrap=${ctx.config.bootstrapServers}"

    override suspend fun open(ctx: KafkaConnectionContext) {
        ctx.adminClient = createVerifiedAdmin(ctx.config)
    }

    override suspend fun onConnected(ctx: KafkaConnectionContext) {
        ctx.messageClients.reopen()
    }

    override suspend fun onBeforeDisconnect(ctx: KafkaConnectionContext) {
        service.cancelScan(ctx.connectionId)
        KafkaModule.clearScanFailures(ctx.connectionId)
    }

    override fun isHandlePresent(ctx: KafkaConnectionContext): Boolean = ctx.adminClient != null

    /** 解掉 `ExecutionException` 包装（KafkaFuture.get 会包一层）。 */
    override fun errorMessage(error: Throwable): String =
        (error as? ExecutionException)?.cause?.message ?: super.errorMessage(error)

    override val connectedEvent: String = KafkaEvents.CONNECTED
    override val connectFailedEvent: String = KafkaEvents.CONNECT_FAILED
    override val disconnectedEvent: String = KafkaEvents.DISCONNECTED

    /**
     * Lag 心跳（快扫，runOnStart）+ 对象慢扫（晚 5 秒起步，把首拍让给心跳）。
     * 间隔来自用户建连接时的配置，**不硬编码**。
     */
    override fun scanJobs(ctx: KafkaConnectionContext): List<ScheduledJob> {
        val config = ctx.config
        return listOf(
            ScheduledJob(
                id = ScheduledJob.idFor(MODULE_ID, ctx.connectionId, JOB_SCAN_LAG),
                intervalMs = config.lagScanIntervalSeconds * 1_000L,
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

    /**
     * 「测试连接」：用给定配置发起一次**临时连接**（describeCluster 验证后立刻关掉，
     * 不影响任何正式连接的状态），失败抛异常 —— 成功返回即连通。
     */
    suspend fun testConnection(config: KafkaConfig) {
        val admin = createVerifiedAdmin(config)
        try {
            // 验证已由 createVerifiedAdmin 完成，这里唯一的义务是把句柄关掉。
            //
            // 显式过一道取消检查：旧实现用 `catch (Throwable)` 包 close，把取消信号
            // 一起吞了 —— 外层 withTimeout 超时后 UI 会当成「测试通过」继续落盘
            // （KafkaConnectionFormState.submit 靠 TimeoutCancellationException 区分
            // 超时与对话框关闭）。
            coroutineContext.ensureActive()
        } finally {
            // NonCancellable：取消到达时 withContext 在**入口**就抛，块体一次都不执行，
            // 句柄照样漏（AdminClient 的 sender / metrics 线程留到进程退出）。
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { admin.close(Duration.ZERO) }
                    .onFailure { error -> log.warn("test connection close failed: error=$error") }
            }
        }
    }

    /**
     * 建 AdminClient 并用 describeCluster 验证连通性。超时由两处兜底：AdminClient
     * 属性（request/default API timeout，见 [KafkaConfig.toAdminProperties]）与
     * `KafkaFuture.get(timeout)` 本身。
     *
     * 失败（含取消）时自己把刚建的句柄关掉再抛，成功时把可用的 AdminClient 交给调用方。
     */
    private suspend fun createVerifiedAdmin(config: KafkaConfig): AdminClient {
        val timeoutMs = config.connectionTimeout * 1_000L
        var admin: AdminClient? = null
        try {
            admin = withContext(Dispatchers.IO) { AdminClient.create(config.toAdminProperties()) }
            withContext(Dispatchers.IO) {
                admin.describeCluster(DescribeClusterOptions().timeoutMs(timeoutMs.toInt()))
                    .nodes()
                    .get(timeoutMs, TimeUnit.MILLISECONDS)
            }
            return admin
        } catch (error: Throwable) {
            runCatching { admin?.close(Duration.ZERO) }
            throw error
        }
    }

    /** 关 AdminClient + 共享消息客户端；失败只记日志（断开路径不能被句柄拖住）。 */
    override suspend fun closeResources(ctx: KafkaConnectionContext) {
        val admin = ctx.adminClient
        ctx.adminClient = null
        // 「mark disposed + 取走引用」在同一监视器里完成，然后才在锁外关。
        // 这一步之后任何 acquire 都会立刻被拒 —— 不会再产生孤儿句柄。
        val clients = ctx.messageClients.take()
        if (admin != null) {
            try {
                withContext(Dispatchers.IO) { admin.close(Duration.ZERO) }
                log.debug("admin client closed: connection=${ctx.connectionId}")
            } catch (error: Throwable) {
                log.warn("admin client close failed: connection=${ctx.connectionId} error=$error")
            }
        }
        // 连接级共享 Producer 带超时 close；Consumer 已改为每次操作独立并在
        // finally 关闭，断开路径不再需要 wakeup / 等业务锁。
        if (clients != null) {
            try {
                clients.closeQuietly()
                log.debug("shared message clients closed: connection=${ctx.connectionId}")
            } catch (error: Throwable) {
                log.warn("shared message clients close failed: connection=${ctx.connectionId} error=$error")
            }
        }
    }
}
