package com.mqclient.aiui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Scrollable list of chat bubbles. Auto-scrolls to the bottom when a new
 * message arrives or when the in-flight assistant bubble grows, mirroring
 * how every modern chat UI keeps the latest reply in view while streaming.
 *
 * 用 [LazyColumn] + `key = message.id`：长会话下只组合视口内的气泡，新消息／流式追加
 * 也不会让整列重算（配合 [AiPanelState] 的按下标更新，只有变化那一条重绘）。
 */
@Composable
fun AiMessageList(
    messages: List<AiMessage>,
    streamingMessageId: String?,
    onFeedback: (messageId: String, useful: Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val last = messages.lastOrNull()
    val streaming = last != null && last.id == streamingMessageId

    // 「新消息 / 换了最后一条」→ 动画滚到底。正在流的那条交给下面，避免两条滚动
    // 互相打断。
    LaunchedEffect(messages.size, last?.id) {
        if (messages.isNotEmpty() && !streaming) {
            // 偏移给个超大值会被夹到列表末尾 —— 于是增长中的末条消息停在它的**底部**，
            // 而不是把这条消息的首行滚到视口顶部。
            listState.animateScrollToItem(messages.lastIndex, scrollOffset = Int.MAX_VALUE)
        }
    }

    // 流式追加：末条的内容每来一个分片就变长，但**不能**用动画跟 —— 动画会在每个
    // 分片（十几毫秒一个）被取消重启动，末条越长越滚不到位。这里用无动画的
    // scrollToItem 贴住底部，动画只留给「新消息」那条路径。
    LaunchedEffect(last?.content?.length) {
        if (streaming) {
            listState.scrollToItem(messages.lastIndex, scrollOffset = Int.MAX_VALUE)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                AiMessageBubble(
                    message = message,
                    onFeedback = { useful -> onFeedback(message.id, useful) },
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(8.dp)
                .fillMaxHeight(),
        )
    }
}