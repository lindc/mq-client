package com.mqclient.rocketmq

import com.mqclient.core.spi.JvmBootstrap

/**
 * JVM 启动最早钩子：在**任何模块类（含 rocketmq-ui）被加载之前**，先把 RocketMQ 5.x
 * 客户端日志落点从默认的 `$HOME/logs/rocketmqlogs` 改到本应用的
 * `~/.mqclient/logs/rocketmqlogs`（见 [RocketMqClientLogConfigurator]）。
 *
 * 为什么必须这么早：RocketMQ 自带的 shaded logback 在**首个日志类加载时**就定格读取
 * `rocketmq.log.root` 并缓存配置，只读一次。启动早期 `LanguageResources.load` 会经
 * `ModuleRegistry.uis` 加载 `RocketMqUI`，进而可能牵出客户端类触发该读取；若那时属性
 * 还没设，默认落点就被锁死，后面再设无效。所以走 [JvmBootstrap]，由
 * `ModuleRegistry.runJvmBootstraps` 在 `main()` 一开头调用，比 `LanguageResources.load`
 * 和 `ServiceAdapter.initialize` 都早。
 *
 * 幂等：[RocketMqClientLogConfigurator.init] 自带幂等守卫，重复调用零开销；模块
 * 初始化里的那次调用（[RocketMqModule.initialize]）是兜底，本钩子才是正主。
 */
class RocketMqJvmBootstrap : JvmBootstrap {
    override fun onJvmStart() {
        RocketMqClientLogConfigurator.init()
    }
}
