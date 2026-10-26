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

    /** 概览 Banner 点击：请求队列页开启「只看异常队列」过滤。 */
    fun requestProblemQueueFilter(connectionId: String) {
        pendingProblemFilter[connectionId] = true
    }

    /** 队列页进入时消费：true = 应开启过滤（消费即清除，只生效一次）。 */
    fun consumeProblemQueueFilter(connectionId: String): Boolean =
        pendingProblemFilter.remove(connectionId) == true
}
