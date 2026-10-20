package com.mqclient.rocketmq

/**
 * RocketMQ 模块自己的事件名常量。命名约定见 app-core 的 EventNames：
 * `<模块名>.<对象>.<动作>`，与 kafka / ibm-mq 的 Events 同构 —— 新增模块 /
 * 新增事件不需要动事件总线，也不需要在任何全局文件里登记。
 */
object RocketMqEvents {

    /** 本模块事件的前缀，等于 `ServiceContext.moduleId + "."`。 */
    const val PREFIX = "rocketmq."

    // ---- 连接生命周期（显式连接模型下由 RocketMqConnectionManager 发出）----

    /** 连接建立成功（AdminExt 可用）；payload: `connectionId` */
    const val CONNECTED = "rocketmq.connection.connected"

    /** 连接已断开（用户主动断开 / 模块关闭）；payload: `connectionId` / `reason` */
    const val DISCONNECTED = "rocketmq.connection.disconnected"

    /** 连接尝试失败；payload: `connectionId` / `message` */
    const val CONNECT_FAILED = "rocketmq.connection.failed"

    // ---- 显式动作请求（发送方是宿主 app-ui，见 app-core ConnectionActions 的事件约定）----

    /** 请求建立连接（来自右键菜单）。payload: `connectionId` */
    const val CONNECT_REQUESTED = "rocketmq.connection.connect"

    /** 请求断开连接。payload: `connectionId` */
    const val DISCONNECT_REQUESTED = "rocketmq.connection.disconnect"

    /** 请求测试连接（临时连接，用完即关）。payload: `connectionId` */
    const val TEST_REQUESTED = "rocketmq.connection.test"

    /**
     * 测试完成（成功失败都发）；payload: `connectionId` / `success`(Boolean) /
     * `message`(String) / `durationMs`(Long)。宿主（app-ui）订阅它弹结果对话框。
     */
    const val TEST_COMPLETED = "rocketmq.connection.test.completed"

    // ---- 定时任务扫描事件（由 JobScheduler 周期性触发，命名对齐 kafka / ibm-mq）----

    /** 对象慢扫完成（clusterInfo / topics / groups 已全量替换进缓存）；payload: `connectionId` / 计数 */
    const val OBJECTS_SCANNED = "rocketmq.scan.objects.completed"

    /** 对象慢扫失败（整轮失败）；payload: `connectionId` / `message` */
    const val OBJECTS_SCAN_FAILED = "rocketmq.scan.objects.failed"

    /** 心跳快扫完成（groupLags 已替换进缓存）；payload: `connectionId` / `groupCount` / `totalLag` */
    const val LAG_SCANNED = "rocketmq.scan.lag.completed"

    /** 心跳快扫失败；payload: `connectionId` / `message` */
    const val LAG_SCAN_FAILED = "rocketmq.scan.lag.failed"
}
