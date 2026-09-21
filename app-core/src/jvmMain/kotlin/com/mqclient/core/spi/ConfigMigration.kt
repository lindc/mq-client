package com.mqclient.core.spi

import com.mqclient.core.Config

/**
 * 模块自己的配置段迁移（SPI，注册在
 * `META-INF/services/com.mqclient.core.spi.ConfigMigration`）。
 *
 * 为什么单独一个 SPI，而不是挂在 `ServiceAdapter` 上：配置迁移必须跑在**模块初始化
 * 之前**（由 `ConfigManager.initOnStartup` 调用，早于 `CoreModule` / `DatabaseManager` /
 * `ServiceAdapter.initialize`）—— 那是另一个生命周期阶段。挂在 `ServiceAdapter` 上的话，
 * 迁移的时机就被绑在一个更晚的阶段上了；而且模块可以只带配置段迁移、没有 SPI 描述模块。
 *
 * 三条硬约束：
 *  1. **纯函数**：只返回新的 [Config]，不落盘、不发事件、不碰数据库 —— 此刻
 *     `CoreModule` / `DatabaseManager` 都还没初始化；写盘由 `ConfigManager` 统一做
 *     （和它自己的读-改-写同一个临界区，所以模块之间不会互相覆盖）；
 *  2. **返回同一个引用 = 无改动**：没有任何模块改动时不该触发一次多余的写盘
 *     （判等用引用，不用 `==`）；
 *  3. **幂等**：每次启动都会调用，撞上已经迁移过的配置必须原样返回。
 */
fun interface ConfigMigration {

    /** 迁移 [config] 里属于本模块的段；无改动时原样返回（引用相等）。 */
    fun migrate(config: Config): Config
}
