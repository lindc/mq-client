package com.mqclient.rocketmq

import com.mqclient.core.ConnectionStatus
import com.mqclient.core.cache.CacheManager

/**
 * 一条 RocketMQ 连接的运行时上下文：显式连接模型下它就是这个连接的全部可变状态。
 * 生命周期与 kafka 的 KafkaConnectionContext 同构（由 [RocketMqConnectionManager]
 * 驱动）：程序启动 / 连接新增时创建（Disconnected、无句柄）；用户显式连接成功后
 * [adminExt] 才被赋值；断开 / 删除 / 模块关闭经 [cleanup] 收走引用，回到 Disconnected。
 *
 * 状态的事实来源有两份但各管各的：[status] 供模块内部守卫（防重复连接），侧边栏
 * 显示走 `ConnectionStore.reportStatus` —— [RocketMqConnectionManager.setStatus]
 * 每次改状态都同时喂这两处。
 */
class RocketMqConnectionContext(
    val connectionId: String,
    @Volatile
    var config: RocketMqConfig,
) {

    /** 连接状态；写入口在 [RocketMqConnectionManager.setStatus]（同时上报核心）。 */
    @Volatile
    var status: ConnectionStatus = ConnectionStatus.Disconnected

    /** 连接成功后的管理客户端；断开 / 失败 / 未连接时为 null。 */
    @Volatile
    var adminClient: RocketMqAdminClient? = null

    /**
     * 本连接专属的扫描缓存（[CacheManager]，扫描链路 write-through、读口只读）。
     * 连接成功时创建、断开 / 失败时置空。
     */
    @Volatile
    var cache: CacheManager? = null

    /** 最近一次失败的原因（连接或后续操作），UI 可以展示；成功连接时清空。 */
    @Volatile
    var lastError: String? = null

    /**
     * 断开 / 删除时的清理：丢弃 AdminExt 引用并**清空缓存**（连接断开不留历史）。
     * AdminExt 本身的 close 由 [RocketMqConnectionManager] 负责（「谁创建谁清理」）。
     */
    fun cleanup() {
        adminClient = null
        cache?.clear()
        cache = null
    }
}
