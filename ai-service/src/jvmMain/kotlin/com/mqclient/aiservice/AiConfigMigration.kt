package com.mqclient.aiservice

import com.mqclient.core.Config
import com.mqclient.core.spi.ConfigMigration

/**
 * 把 AI 段的启动迁移接到 core 的配置迁移钩子上（SPI 注册见
 * `resources/META-INF/services/com.mqclient.core.spi.ConfigMigration`）。
 *
 * 迁移规则本身在 [AiConnectionRepository.migrateIfNeeded]（复用它已有的引用判等约定：
 * 无需迁移时返回同一个 [com.mqclient.core.AiConfig] 实例），这里只负责把「无改动」
 * 如实往上传 —— core 靠引用相等决定要不要落盘。
 *
 * 纯函数：不发事件、不落盘、不碰数据库（此刻 `CoreModule` / `DatabaseManager` 都还没初始化）。
 */
class AiConfigMigration : ConfigMigration {

    override fun migrate(config: Config): Config {
        val migrated = AiConnectionRepository.migrateIfNeeded(config.ai)
        return if (migrated === config.ai) config else config.copy(ai = migrated)
    }
}
