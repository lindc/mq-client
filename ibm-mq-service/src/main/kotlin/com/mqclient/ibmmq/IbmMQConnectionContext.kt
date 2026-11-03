package com.mqclient.ibmmq

import com.ibm.mq.MQQueueManager
import com.mqclient.mq.base.AbstractConnectionContext
import kotlinx.coroutines.sync.Mutex

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
    connectionId: String,
    config: IbmMQConfig,
) : AbstractConnectionContext<IbmMQConfig>(connectionId, config) {

    /** 连接成功后的 MQ 句柄；断开 / 失败 / 未连接时为 null。 */
    @Volatile
    var mqConnection: MQQueueManager? = null

    /**
     * 该连接 **MQI 调用的唯一互斥锁**：PCF 扫描（慢扫 / 心跳 / 单对象查询 / MQSC 控制台）
     * 与消息发送 / 浏览在这一把锁上串行。
     *
     * 为什么必须是每连接一把、而不是全局一把：`MQQueueManager` 的一条 TCP 通道内部的
     * MQI 请求/应答是有状态串行的，两个协程同时在其上 `accessQueue` / `put` / PCF agent
     * `send` 会互相踩对方的应答（表现是随机 MQRC、对象凭空消失、慢扫结果被覆盖）。
     * 锁的粒度是连接，所以 A 连接的慢扫不会挡住 B 连接的消息浏览。
     *
     * 为什么不用 [com.mqclient.core.concurrent.KeyedMutex]：锁必须与上下文同生共死 ——
     * `disconnect` 把 `mqConnection` 置 null 之后，新连接不能拿到上一轮残留的锁状态。
     *
     * 不参与锁的两条路径（有意为之）：
     *  - [IbmMQConnectionManager.disconnectNow] / 模块关闭：一条卡死的 MQI 调用会把断开
     *    路径一起拖死，断开必须立刻生效（见其 KDoc）；
     *  - `PcfObjectScanner.scanAll` 内部 11 类并发查询：锁在 service 入口持有一整轮，
     *    内部每类一个独立 agent 仍可并发（在 `withAgent` 取锁会退化成串行慢扫）。
     */
    val mqiLock: Mutex = Mutex()

    /**
     * 断开 / 删除时的清理：丢弃 MQ 句柄引用；缓存与失败计数由基类
     * [AbstractConnectionContext.cleanup] 处理（句柄的 disconnect 由 manager 的
     * closeResources 负责 —— 「谁创建谁清理」）。
     */
    override fun cleanup() {
        mqConnection = null
        super.cleanup()
    }
}
