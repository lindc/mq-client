package com.mqclient.mq.base

/**
 * MQ 通用常量。
 *
 * 约定与 [com.mqclient.core.ServiceAdapter.category] 的文档一致：类目取值由
 * 模块侧持有（app-core 不集中定义），MQ 系模块统一从这里的 [CATEGORY] 取，
 * 避免 "MQ" 字符串散落各处。
 */
object MQConstants {
    /** 新建连接对话框中 MQ 系模块的类目名。 */
    const val CATEGORY = "MQ"

    // 通用默认值（各模块的 Config 默认值逐步对齐到这里；现有模块的取值保持不变）。
    const val DEFAULT_MESSAGE_SCAN_INTERVAL_SECONDS = 10
    const val DEFAULT_OBJECT_SCAN_INTERVAL_MINUTES = 60
    const val DEFAULT_CONNECTION_TIMEOUT_SECONDS = 30
}
