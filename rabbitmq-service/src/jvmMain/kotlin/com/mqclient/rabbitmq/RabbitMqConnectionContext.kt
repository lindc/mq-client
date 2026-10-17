package com.mqclient.rabbitmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.cache.CacheManager

/**
 * 一条 RabbitMQ 连接的运行时上下文：显式连接模型下它就是这个连接的全部可变状态。
 * 生命周期与 KafkaConnectionContext 同构（由 [RabbitMqConnectionManager] 驱动）：
 *  - 程序启动 / 连接新增时创建：状态 [ConnectionStatus.Disconnected]，没有 HTTP 客户端；
 *  - 用户显式连接成功：状态 Connected，[admin] 才被赋值；
 *  - 断开 / 删除 / 模块关闭：[cleanup] 收走句柄引用，回到 Disconnected。
 *
 * 状态的事实来源有两份但各管各的：[status] 供模块内部守卫（防重复连接），侧边栏
 * 显示走 `ConnectionStore.reportStatus` —— RabbitMqConnectionManager.setStatus 每次
 * 改状态都同时喂这两处。
 *
 * [config] 是连接时的配置快照；`connection.updated` 事件到来时由 manager 原地换新
 * （val 是为了构造期不可变，运行期换快照经 `@Volatile var` 保证可见性）。
 */
class RabbitMqConnectionContext(
    val connectionId: String,
    @Volatile
    var config: RabbitMqConfig,
) {

    /** 连接状态；写入口在 RabbitMqConnectionManager.setStatus（同时上报核心）。 */
    @Volatile
    var status: ConnectionStatus = ConnectionStatus.Disconnected

    /**
     * 连接成功后的管理 API 客户端（Ktor HttpClient 挂在它内部）；断开 / 失败 / 未连接
     * 时为 null。RabbitMQ 第一阶段没有常驻 AMQP 连接 —— 唯一的「句柄」就是它。
     */
    @Volatile
    var admin: RabbitMqAdminClient? = null

    /**
     * 本连接专属的扫描缓存（[CacheManager]，扫描链路 write-through、读口只读）。
     * 连接成功时创建、断开 / 失败时置空 —— 与 kafka / ibm-mq 同一生命周期。
     */
    @Volatile
    var cache: CacheManager? = null

    /** 最近一次失败的原因（连接或后续操作），UI 可以展示；成功连接时清空。 */
    @Volatile
    var lastError: String? = null

    /**
     * 断开 / 删除时的清理：丢弃 admin 引用并**清空缓存**（连接断开不留历史）。
     * HttpClient 本身的 close 由 [RabbitMqConnectionManager] 负责（「谁创建谁清理」：
     * 句柄是它连的）。
     */
    fun cleanup() {
        admin = null
        cache?.clear()
        cache = null
    }
}
