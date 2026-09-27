package com.mqclient.core.topology

/**
 * 拓扑数据提供方的注册表：按 moduleId 索引各中间件模块实现的 [TopologyProvider]。
 *
 * 各中间件模块在初始化时调用 [register] 登记自己的实现；拓扑 UI 只持 [TopologyProvider]
 * 契约与 graph-core 的通用图类型，通过连接的 serviceId 查表拿到对应 provider 并调用
 * [TopologyProvider.buildGraph]，从而与具体中间件解耦。
 */
object TopologyProviderRegistry {
    private val providers = LinkedHashMap<String, TopologyProvider>()
    private val lock = Any()

    fun register(moduleId: String, provider: TopologyProvider) = synchronized(lock) {
        providers[moduleId] = provider
    }

    fun unregister(moduleId: String) = synchronized(lock) {
        providers.remove(moduleId)
    }

    operator fun get(moduleId: String): TopologyProvider? = synchronized(lock) {
        providers[moduleId]
    }
}
