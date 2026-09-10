package com.mqclient.aipanel

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Scrollable list of chat bubbles. Auto-scrolls to the bottom when a new
 * message arrives or when the in-flight assistant bubble grows, mirroring
 * how every modern chat UI keeps the latest reply in view while streaming.
 */
@Composable
fun AiMessageList(
    messages: List<AiMessage>,
    onFeedback: (messageId: String, useful: Boolean?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val last = messages.lastOrNull()

    // Re-fire when either the list size grows or the last message's length
    // changes (i.e. an assistant message is streaming in).
    LaunchedEffect(messages.size, last?.content?.length) {
        if (messages.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            messages.forEach { msg ->
                AiMessageBubble(
                    message = msg,
                    onFeedback = { useful -> onFeedback(msg.id, useful) },
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(8.dp)
                .fillMaxHeight(),
        )
    }
}