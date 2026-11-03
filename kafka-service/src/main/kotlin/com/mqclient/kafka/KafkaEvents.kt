package com.mqclient.kafka

/**
 * Kafka 模块自己的事件名常量。命名约定见 app-core 的 EventNames：
 * `<模块名>.<对象>.<动作>`，与 ibm-mq 的 IbmMQEvents 同构 —— 新增模块 / 新增事件
 * 不需要动事件总线，也不需要在任何全局文件里登记。
 */
object KafkaEvents {

    /** 本模块事件的前缀，等于 `ServiceContext.moduleId + "."`。 */
    const val PREFIX = "kafka."

    // ---- 连接生命周期（显式连接模型下由 KafkaConnectionManager 发出）----
    // 连接状态的事实来源是 ConnectionStore（reportStatus 上报，侧边栏直接订阅）；
    // 这三个事件是「刚发生了什么」，给需要响应式动作的订阅方用。

    /** 连接建立成功（AdminClient 可用）；payload: `connectionId` */
    const val CONNECTED = "kafka.connection.connected"

    /** 连接已断开（用户主动断开 / 模块关闭）；payload: `connectionId` / `reason` */
    const val DISCONNECTED = "kafka.connection.disconnected"

    /** 连接尝试失败；payload: `connectionId` / `message` */
    const val CONNECT_FAILED = "kafka.connection.failed"

    // ---- 显式动作请求（发送方是宿主 app-ui，见 app-core ConnectionActions 的事件约定）----
    // 模块在 KafkaModule.initialize 里精确订阅这三个名字并执行；不订阅的模块等于
    // 不支持对应动作。payload 统一为 `connectionId`。

    /** 请求建立连接（来自右键菜单）。payload: `connectionId` */
    const val CONNECT_REQUESTED = "kafka.connection.connect"

    /** 请求断开连接。payload: `connectionId` */
    const val DISCONNECT_REQUESTED = "kafka.connection.disconnect"

    /** 请求测试连接（临时连接，用完即关）。payload: `connectionId` */
    const val TEST_REQUESTED = "kafka.connection.test"

    /**
     * 测试完成（成功失败都发）；payload: `connectionId` / `success`(Boolean) /
     * `message`(String) / `durationMs`(Long)。宿主（app-ui）订阅它弹结果对话框。
     */
    const val TEST_COMPLETED = "kafka.connection.test.completed"

    // ---- 定时任务扫描事件（由 JobScheduler 周期性触发，命名对齐 ibm-mq）----
    // 订阅方（UI）收到 completed 后读缓存刷新 —— 缓存优先、事件驱动的统一消费模式。

    /** 对象慢扫完成（brokers / topics / groups 已全量替换进缓存）；payload: `connectionId` / 计数 */
    const val OBJECTS_SCANNED = "kafka.scan.objects.completed"

    /** 对象慢扫失败（整轮失败，如 describeCluster 不通）；payload: `connectionId` / `message` */
    const val OBJECTS_SCAN_FAILED = "kafka.scan.objects.failed"

    /** Lag 心跳扫完成（groupLags 已替换进缓存）；payload: `connectionId` / `groupCount` / `totalLag` */
    const val LAG_SCANNED = "kafka.scan.lag.completed"

    /** Lag 心跳扫失败；payload: `connectionId` / `message` */
    const val LAG_SCAN_FAILED = "kafka.scan.lag.failed"
}
