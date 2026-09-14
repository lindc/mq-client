package com.mqclient.ibmmq

import com.mqclient.core.cache.CacheManager

/**
 * 一条 IBM MQ 连接的运行时上下文：本连接专属的状态都挂在这里。
 *
 * 重点是 [cache] —— **每条连接一个独立的 [CacheManager] 实例**，实例之间互不相干：
 *  - 创建：连接出现时由 [IbmMQModule] 建立（`core.connection.added` 或启动补注册）；
 *  - 清理：连接被删除时 [IbmMQModule] 先把它从注册表摘掉，再调 [cleanup]；
 *    `shutdown` 时对全部连接做同样的事。
 *
 * 因为实例本身隔离了数据，缓存 key 不需要任何连接前缀（见 [IbmMQService] 的用法）。
 *
 * 配置（host / port / options）不在这里存快照：权威来源是 `ConnectionStore`，
 * 复制一份就得再处理 `core.connection.updated` 的同步。将来接真 MQ 客户端时，
 * 「连接被删就必须跟着销毁」的运行时资源（连接 / 会话 / 通道句柄）加到这个类里。
 */
class IbmMQConnectionContext(val connectionId: String) {

    /**
     * 本连接专属的缓存实例。
     *
     * key 示例：`"queues"`（队列快照）、`"queue.<name>.messageCount"`（单队列消息数）。
     */
    val cache = CacheManager()

    /**
     * 连接销毁时的清理：清空缓存（「谁创建谁清理」里的那一半）。
     *
     * 只清数据、不动别的：从 [IbmMQModule] 的注册表摘除由调用方负责，定时任务与
     * 订阅的收尾各有各的入口（见 `IbmMQModule` 的连接删除分支与 `shutdown`）。
     */
    fun cleanup() {
        cache.clear()
    }
}
