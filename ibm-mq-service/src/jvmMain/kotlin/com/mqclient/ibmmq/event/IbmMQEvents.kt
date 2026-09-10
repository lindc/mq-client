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

    /** 开始扫描；payload: `connectionId` */
    const val SCAN_STARTED = "ibm-mq.scan.started"

    /** 扫描完成；payload: `connectionId` / `queueCount` / `totalMessages` */
    const val SCAN_COMPLETED = "ibm-mq.scan.completed"

    /** 扫描失败；payload: `connectionId` / `message` */
    const val SCAN_FAILED = "ibm-mq.scan.failed"
}
