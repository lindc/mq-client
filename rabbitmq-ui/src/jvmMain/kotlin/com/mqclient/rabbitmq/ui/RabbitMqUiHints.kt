package com.mqclient.rabbitmq.ui

import java.util.concurrent.ConcurrentHashMap

/**
 * 模块内跨面板的轻量 UI 提示（TopologyFocusHolder 的模块内缩小版）：概览页的健康度
 * Banner 点击「消息积压」告警时写入，队列列表页进入时消费并清除。
 *
 * 放 rabbitmq-ui 模块内闭环（app-core 的 holder 是给「宿主不依赖模块 UI」的跨层
 * 跳转用的；这里发起方与消费方同模块，不需要走 core）。
 */
internal object RabbitMqUiHints {

    /** connectionId → 待消费的「只看异常队列」过滤提示。 */
    private val pendingProblemFilter = ConcurrentHashMap<String, Boolean>()

    /** connectionId → 待消费的「打开对象属性」焦点（"exchange" / "queue" → 对象名）。 */
    private val pendingObjectFocus = ConcurrentHashMap<String, Pair<String, String>>()

    /** 概览 Banner 点击：请求队列页开启「只看异常队列」过滤。 */
    fun requestProblemQueueFilter(connectionId: String) {
        pendingProblemFilter[connectionId] = true
    }

    /** 队列页进入时消费：true = 应开启过滤（消费即清除，只生效一次）。 */
    fun consumeProblemQueueFilter(connectionId: String): Boolean =
        pendingProblemFilter.remove(connectionId) == true

    /**
     * DLX / routing key 链接点击：请求目标面板自动打开对象属性对话框。
     * 发起方先自行 onDismiss + 发 OBJECT_TAB_OPEN_REQUESTED 切主 Tab，这里只负责
     * 「面板进来后开哪个属性」；目标面板在数据就绪后消费（查不到对象则静默丢弃）。
     */
    fun requestObjectFocus(connectionId: String, kind: String, name: String) {
        pendingObjectFocus[connectionId] = kind to name
    }

    /**
     * 面板进入时消费：仅当待处理焦点的 kind 与本面板匹配时返回名称并清除
     * （不匹配则原样保留，等目标面板来消费）。
     */
    fun consumeObjectFocus(connectionId: String, kind: String): String? =
        pendingObjectFocus[connectionId]
            ?.takeIf { it.first == kind }
            ?.let { pendingObjectFocus.remove(connectionId)?.second }
}
