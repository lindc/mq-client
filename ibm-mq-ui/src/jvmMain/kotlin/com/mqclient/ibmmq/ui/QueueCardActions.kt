package com.mqclient.ibmmq.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import com.mqclient.mqui.objectlist.CardAction

/**
 * 构造 IBM MQ 队列卡片上的操作按钮。
 *
 * 每个操作由 ibm-mq-ui 定义语义（图标 / 文案 / 回调），mq-ui 只负责渲染。
 * 「复制名称」是当前真实可用的动作；属性 / 消息处理是宿主回调钩子——
 * 对应的 Dialog 落地后由 IbmMqUI 注入真实实现。
 */
object QueueCardActions {

    /**
     * 构造队列卡片的操作按钮列表。
     *
     * @param queueName 队列名（回调里带回，调用方免闭包捕获歧义）。
     * @param onCopyName 复制队列名到剪贴板。
     * @param onOpenProperties 打开队列属性 Dialog 的回调。
     * @param onOpenMessages 打开消息处理 Dialog 的回调。
     */
    fun create(
        queueName: String,
        onCopyName: () -> Unit,
        onOpenProperties: () -> Unit,
        onOpenMessages: () -> Unit,
    ): List<CardAction> = listOf(
        CardAction(
            id = "queue.copy.name",
            icon = Icons.Default.ContentCopy,
            tooltip = "queue_copy_name",
            onClick = onCopyName,
        ),
        CardAction(
            id = "queue.properties",
            icon = Icons.Default.Description,
            tooltip = "queue_action_properties",
            onClick = onOpenProperties,
        ),
        CardAction(
            id = "queue.messages",
            icon = Icons.Default.Email,
            tooltip = "queue_action_messages",
            onClick = onOpenMessages,
        ),
    )
}
