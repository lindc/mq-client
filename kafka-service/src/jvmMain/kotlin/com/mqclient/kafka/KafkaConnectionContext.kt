package com.mqclient.kafka

import com.mqclient.core.ConnectionStatus
import org.apache.kafka.clients.admin.AdminClient

/**
 * 一条 Kafka 连接的运行时上下文：显式连接模型下它就是这个连接的全部可变状态。
 * 生命周期与 ibm-mq 的 IbmMQConnectionContext 同构（由 [KafkaConnectionManager] 驱动）：
 *  - 程序启动 / 连接新增时创建：状态 [ConnectionStatus.Disconnected]，没有 AdminClient；
 *  - 用户显式连接成功：状态 Connected，[adminClient] 才被赋值；
 *  - 断开 / 删除 / 模块关闭：[cleanup] 收走句柄引用，回到 Disconnected。
 *
 * 状态的事实来源有两份但各管各的：[status] 供模块内部守卫（防重复连接），侧边栏
 * 显示走 `ConnectionStore.reportStatus` —— [KafkaConnectionManager.setStatus] 每次
 * 改状态都同时喂这两处。
 *
 * [config] 是连接时的配置快照；`connection.updated` 事件到来时由 manager 原地换新
 * （val 是为了构造期不可变，运行期换快照经 `@Volatile var` 保证可见性）。
 */
class KafkaConnectionContext(
    val connectionId: String,
    @Volatile
    var config: KafkaConfig,
) {

    /** 连接状态；写入口在 [KafkaConnectionManager.setStatus]（同时上报核心）。 */
    @Volatile
    var status: ConnectionStatus = ConnectionStatus.Disconnected

    /** 连接成功后的 AdminClient；断开 / 失败 / 未连接时为 null。 */
    @Volatile
    var adminClient: AdminClient? = null

    /** 最近一次失败的原因（连接或后续操作），UI 可以展示；成功连接时清空。 */
    @Volatile
    var lastError: String? = null

    /**
     * 断开 / 删除时的清理：丢弃 AdminClient 引用。AdminClient 本身的 close 由
     * [KafkaConnectionManager] 负责（「谁创建谁清理」：句柄是它连的）。
     */
    fun cleanup() {
        adminClient = null
    }
}
