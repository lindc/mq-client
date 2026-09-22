package com.mqclient.ibmmq.event

/**
 * IBM MQ 模块自己的事件名常量。
 *
 * 放在模块内部而不是 app-core：新增模块 / 新增事件都不需要动事件总线，也不需要在
 * 任何全局文件里登记 —— 这是「字符串事件名」这套设计的主要动机。
 *
 * 命名约定见 app-core 的 EventNames：`<模块名>.<对象>.<动作>`。
 */
object IbmMQEvents {

    /** 本模块事件的前缀，等于 `ServiceContext.moduleId + "."`。 */
    const val PREFIX = "ibm-mq."

    // ---- 连接生命周期（显式连接模型下由 IbmMQConnectionManager 发出）----
    // 连接状态的事实来源是 ConnectionStore（reportStatus 上报，侧边栏直接订阅）；
    // 这三个事件是「刚发生了什么」，给需要响应式动作的订阅方用（如日志、将来的
    // 自动重连）。

    /** 连接建立成功（cache 与定时扫描任务已就位）；payload: `connectionId` */
    const val CONNECTED = "ibm-mq.connection.connected"

    /** 连接已断开（用户主动断开 / 模块关闭）；payload: `connectionId` / `reason` */
    const val DISCONNECTED = "ibm-mq.connection.disconnected"

    /** 连接尝试失败；payload: `connectionId` / `message` */
    const val CONNECT_FAILED = "ibm-mq.connection.failed"

    /** 开始扫描；payload: `connectionId` */
    const val SCAN_STARTED = "ibm-mq.scan.started"

    /** 扫描完成；payload: `connectionId` / `queueCount` / `totalMessages` */
    const val SCAN_COMPLETED = "ibm-mq.scan.completed"

    /** 扫描失败；payload: `connectionId` / `message` */
    const val SCAN_FAILED = "ibm-mq.scan.failed"

    // ---- 定时任务扫描事件（由 JobScheduler 周期性触发）----
    // 与上面的手动扫描分开命名：订阅方（UI）能区分「用户点的」和「定时自己跑的」，
    // 后者不该抢用户当前视图的焦点。

    /** 定时扫完消息数量；payload: `connectionId` / `queueCount` / `totalMessages` */
    const val MESSAGES_SCANNED = "ibm-mq.scan.messages.completed"

    /** 消息数量扫描失败；payload: `connectionId` / `message` */
    const val MESSAGES_SCAN_FAILED = "ibm-mq.scan.messages.failed"

    /** 定时扫完对象列表；payload: `connectionId` / `queueCount` / `topicCount` / `listenerCount` */
    const val OBJECTS_SCANNED = "ibm-mq.scan.objects.completed"

    /** 对象列表扫描失败；payload: `connectionId` / `message` */
    const val OBJECTS_SCAN_FAILED = "ibm-mq.scan.objects.failed"
}
