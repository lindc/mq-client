package com.mqclient.olap.base

/**
 * OLAP 协议族的统一分类常量（对标 mq-base 的 [com.mqclient.mq.base.MQConstants]）。
 *
 * 约定与 [com.mqclient.core.ServiceAdapter.category] 的文档一致：类目取值由模块侧
 * 持有（app-core 不集中定义），OLAP 系模块统一从这里的 [CATEGORY] 取，避免
 * "OLAP" 字符串散落各处。
 *
 * 派生常量是日志 / 事件 / i18n / 性能打点的**单一来源**：
 *  - [LOG_TAG_PREFIX] / [PERF_TAG_PREFIX]：日志与 `[OLAP-Perf]` 打点的前缀，
 *    对齐 MQ 系的 `[MQ-Perf]` 口径（见 [com.mqclient.olap.base.perf.OlapPerfMarker]）；
 *  - [EVENT_NAMESPACE]：EventBus 事件名前缀，事件名约定 `<namespace>.<对象>.<动作>`
 *    （与 `core.` / `ibm-mq.` 同风格，见 [com.mqclient.core.event.EventNames]）；
 *  - [I18N_KEY_PREFIX]：错误翻译键前缀，`OlapErrorCode` 拼键、展示端
 *    `Throwable.userMessage()` 按当前语言表翻译。
 *
 * ⚠️ 下游模块**禁止**硬编码这些字面量，一律引用本对象。
 */
object OlapCategory {

    /** 新建连接对话框中 OLAP 系模块的类目名。 */
    const val CATEGORY: String = "OLAP"

    /** 模块日志 tag 前缀（完整 tag = 前缀 + 模块 id，如 `[OLAP]clickhouse`）。 */
    const val LOG_TAG_PREFIX: String = "[OLAP]"

    /** 性能打点前缀，对齐 MQ 系的 `[MQ-Perf]`（分段见 OlapPerfMarker.SECTIONS）。 */
    const val PERF_TAG_PREFIX: String = "[OLAP-Perf]"

    /** EventBus 事件名命名空间（事件名 = `<namespace>.<对象>.<动作>`）。 */
    const val EVENT_NAMESPACE: String = "olap"

    /** i18n 翻译键前缀（完整键 = 前缀 + 子路径，如 `olap.err.timeout`）。 */
    const val I18N_KEY_PREFIX: String = "olap."
}
