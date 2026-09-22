package com.mqclient.ibmmq

import com.ibm.mq.MQQueueManager
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.cache.CacheManager

/**
 * 一条 IBM MQ 连接的运行时上下文：**显式连接模型**下，它就是这个连接的全部可变状态。
 *
 * 生命周期（由 [IbmMQConnectionManager] 驱动）：
 *  - 程序启动 / 连接新增时创建：状态 [ConnectionStatus.Disconnected]，**没有 cache、
 *    没有 MQ 句柄** —— 只是一份「配置快照 + 空状态」；
 *  - 用户显式连接成功：状态 Connected，[cache] 与 [mqConnection] 才被赋值；
 *  - 断开 / 删除 / 模块关闭：[cleanup] 收走 cache 与句柄，回到 Disconnected。
 *
 * 状态的事实来源有两份但各管各的：[status] 供模块内部守卫（扫描跳过 / 防重复连接），
 * 侧边栏显示走 [ConnectionStore.reportStatus]（唯一入口，见 Connection.kt）——
 * [IbmMQConnectionManager.setStatus] 每次改状态都同时喂这两处。
 *
 * [config] 是连接时的配置快照；`connection.updated` 事件到来时由 manager 原地换新
 * （val 是为了构造期不可变，运行期换快照经 `@Volatile var` 保证可见性）。
 *
 * 配置本身（host / port / options）的权威来源是 `ConnectionStore`，这里只存连接
 * 期间用的快照 —— 不复制一份就得处理运行中配置漂移，复制了就得在 updated 事件里
 * 换快照，后者更简单。
 */
class IbmMQConnectionContext(
    val connectionId: String,
    @Volatile
    var config: IbmMQConfig,
) {

    /** 连接状态；写入口在 [IbmMQConnectionManager.setStatus]（同时上报核心）。 */
    @Volatile
    var status: ConnectionStatus = ConnectionStatus.Disconnected

    /** 连接成功后的 MQ 句柄；断开 / 失败 / 未连接时为 null。 */
    @Volatile
    var mqConnection: MQQueueManager? = null

    /** 最近一次失败的原因（连接或扫描），UI 可以展示；成功连接时清空。 */
    @Volatile
    var lastError: String? = null

    /**
     * 本连接专属的缓存实例：**连接成功时才创建**，断开时清空并置 null ——
     * 「没连上就没有数据」在缓存层也成立。扫描链路写、读入口只读缓存不碰 MQ。
     */
    @Volatile
    var cache: CacheManager? = null

    /**
     * 断开 / 删除时的清理：清空 cache、丢弃 MQ 句柄引用。
     * MQ 句柄的 disconnect、定时任务的取消由 [IbmMQConnectionManager] 负责
     * （「谁创建谁清理」：句柄是它连的，任务是它注册的）。
     */
    fun cleanup() {
        cache?.clear()
        cache = null
        mqConnection = null
    }
}
