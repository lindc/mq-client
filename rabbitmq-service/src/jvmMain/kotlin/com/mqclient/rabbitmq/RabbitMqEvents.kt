package com.mqclient.rabbitmq

/**
 * RabbitMQ 模块自己的事件名常量。命名约定见 app-core 的 EventNames：
 * `<模块名>.<对象>.<动作>`，与 KafkaEvents 同构 —— 新增模块 / 新增事件不需要动
 * 事件总线，也不需要在任何全局文件里登记。
 */
object RabbitMqEvents {

    /** 本模块事件的前缀，等于 `ServiceContext.moduleId + "."`。 */
    const val PREFIX = "rabbitmq."

    // ---- 连接生命周期（显式连接模型下由 RabbitMqConnectionManager 发出）----
    // 连接状态的事实来源是 ConnectionStore（reportStatus 上报，侧边栏直接订阅）；
    // 这三个事件是「刚发生了什么」，给需要响应式动作的订阅方用。

    /** 连接建立成功（管理 API overview 验证通过）；payload: `connectionId` */
    const val CONNECTED = "rabbitmq.connection.connected"

    /** 连接已断开（用户主动断开 / 模块关闭）；payload: `connectionId` / `reason` */
    const val DISCONNECTED = "rabbitmq.connection.disconnected"

    /** 连接尝试失败；payload: `connectionId` / `message` */
    const val CONNECT_FAILED = "rabbitmq.connection.failed"

    // ---- 显式动作请求（发送方是宿主 app-ui，见 app-core ConnectionActions 的事件约定）----
    // 模块在 RabbitMqModule.initialize 里精确订阅这三个名字并执行；不订阅的模块等于
    // 不支持对应动作。payload 统一为 `connectionId`。

    /** 请求建立连接（来自右键菜单）。payload: `connectionId` */
    const val CONNECT_REQUESTED = "rabbitmq.connection.connect"

    /** 请求断开连接。payload: `connectionId` */
    const val DISCONNECT_REQUESTED = "rabbitmq.connection.disconnect"

    /** 请求测试连接（临时连接，用完即关）。payload: `connectionId` */
    const val TEST_REQUESTED = "rabbitmq.connection.test"

    /**
     * 测试完成（成功失败都发）；payload: `connectionId` / `success`(Boolean) /
     * `message`(String) / `durationMs`(Long)。宿主（app-ui）订阅它弹结果对话框。
     */
    const val TEST_COMPLETED = "rabbitmq.connection.test.completed"

    // ---- 定时任务扫描事件（由 JobScheduler 周期性触发，命名对齐 kafka / ibm-mq）----
    // 订阅方（UI）收到 completed 后读缓存刷新 —— 缓存优先、事件驱动的统一消费模式。

    /** 对象慢扫完成（exchanges / queues / bindings 已全量替换进缓存）；payload: `connectionId` / 计数 */
    const val OBJECTS_SCANNED = "rabbitmq.scan.objects.completed"

    /** 对象慢扫失败（整轮失败）；payload: `connectionId` / `message` */
    const val OBJECTS_SCAN_FAILED = "rabbitmq.scan.objects.failed"

    /** 状态快扫完成（queueStatus 已替换进缓存）；payload: `connectionId` / `queueCount` / `totalMessages` */
    const val STATUS_SCANNED = "rabbitmq.scan.status.completed"

    /** 状态快扫失败；payload: `connectionId` / `message` */
    const val STATUS_SCAN_FAILED = "rabbitmq.scan.status.failed"
}
