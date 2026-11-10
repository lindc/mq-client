package com.mqclient.olap.base.connection

/**
 * 一条已建立的 OLAP 会话（对标 MQ 系的 `MQQueueManager` / `AdminClient` 句柄概念）。
 *
 * 会话是**重量级对象**：连接级共享（由连接管理器持有），绝不做单次查询后关闭 ——
 * 这是本项目「底层连接共享，业务句柄用完即毁」规范里共享的那一半。
 * 轻量句柄（Query / ResultSet / ResultBatch）由查询执行器每次操作独立创建，
 * 见 `com.mqclient.olap.base.query.AbstractQueryExecutor`。
 *
 * 实现注意：
 *  - [close] 必须自行兜异常（只记日志）—— 断开路径不能被一个关不掉的句柄中断；
 *  - [close] 应幂等（重复调用无副作用）；
 *  - [isUsable] 用于区分「真没连接」与「单次请求失败」（与 MQ 系
 *    `isHandlePresent` 同语义：句柄不在时，扫描失败不进入连续失败阈值）。
 */
interface AnalyticsSession {

    /** 关闭会话并释放底层资源（连接 / 连接池 / HTTP client）。幂等，自行兜异常。 */
    suspend fun close()

    /** 底层句柄是否仍然可用（半开连接 / 已被服务端断开时返回 false）。 */
    fun isUsable(): Boolean
}
