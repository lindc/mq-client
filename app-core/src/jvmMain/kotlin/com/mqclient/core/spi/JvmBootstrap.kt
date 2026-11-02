package com.mqclient.core.spi

/**
 * JVM 启动最早阶段的钩子（SPI，注册在
 * `META-INF/services/com.mqclient.core.spi.JvmBootstrap`）。
 *
 * 用途：某些第三方库在**首个日志类被加载时**就定格读取系统属性，且只读一次、早于任何
 * 模块代码。典型的就是 RocketMQ 5.x 客户端自带的 shaded logback：它在 `rocketmq.log.root`
 * 缺省时把客户端日志写到 `$HOME/logs/rocketmqlogs`，而这一读取发生在第一个 RocketMQ
 * 类初始化之时。若不在那之前设好系统属性，库的默认落点就被锁死，后面再改无效
 * （见 [com.mqclient.rocketmq.RocketMqClientLogConfigurator]）。
 *
 * 由 [com.mqclient.core.ModuleRegistry.runJvmBootstraps] 在 `main()` 最开头调用，
 * 远早于 `ConfigStore.initOnStartup` / `LanguageResources.load`（它会经
 * `ModuleRegistry.uis` 加载各模块的 UI 类）/ 各 `ServiceAdapter.initialize`。
 *
 * 硬约束：**只设 JVM 级系统属性或做极轻量、无副作用的准备**；此刻 `CoreModule` /
 * 数据库 / 事件总线都还没起来，禁止发事件、碰数据库、依赖任何模块状态。单个钩子失败
 * 只记日志、继续，不让启动卡住（见 [ModuleRegistry.runJvmBootstraps]）。
 */
fun interface JvmBootstrap {

    /** JVM 启动最早期执行（早于一切模块初始化）。 */
    fun onJvmStart()
}
