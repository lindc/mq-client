package com.mqclient.mqui.state

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.objectlist.ObjectListEmptyState
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.PrimaryButton

/**
 * 通用「加载状态」展示组件（mq-ui 的 UI 层）：把 [LoadState] 四态映射到界面。
 *
 * - [LoadState.Idle]：不渲染（占位态，调用方可自行决定是否展示骨架 / 提示）。
 * - [LoadState.Loading]：居中 [CircularProgressIndicator] + [loadingMessage]
 *   （复用 mq-ui 现有进度圈视觉，不做骨架屏，保持统一不过度设计）。
 * - [LoadState.Error]：警告图标 + [LoadState.Error.message]（红色）+ 可选的
 *   [LoadState.Error.cause] 一行小字（仅 message，不给堆栈）；若 [onRetry] 非空，
 *   显示「重试」按钮（复用公共 i18n key `common_retry`），点击触发 [onRetry]。
 * - [LoadState.Success]：[emptyCheck] 为真 → [emptyContent]（默认复用
 *   [ObjectListEmptyState]）；否则渲染 [content]。
 *
 * 组件本身**不内置** i18n key：[loadingMessage] 由调用方传 `stringResource("…")`，
 * 保持通用、跨模块复用；`common_retry` / `object_list_empty` 属公共表，组件内直用。
 *
 * @param T 成功时数据的类型。
 * @param state 当前状态。
 * @param onRetry 重试回调；为 null 时错误态不显示重试按钮。
 * @param loadingMessage 加载态显示的文案（建议走 i18n）。
 * @param emptyCheck 判定成功数据为空的函数，默认对 Collection 判 `isEmpty()`。
 * @param emptyContent 空态 Composable（默认 [ObjectListEmptyState]）。
 * @param content 成功且非空时的内容。
 */
@Composable
fun <T> LoadStateContent(
    state: LoadState<T>,
    onRetry: (() -> Unit)? = null,
    loadingMessage: String,
    modifier: Modifier = Modifier,
    emptyCheck: (T) -> Boolean = { it is Collection<*> && it.isEmpty() },
    emptyContent: @Composable () -> Unit = { ObjectListEmptyState() },
    content: @Composable (T) -> Unit,
) {
    when (state) {
        is LoadState.Idle -> Unit
        is LoadState.Loading -> LoadingView(message = loadingMessage, modifier = modifier)
        is LoadState.Error -> ErrorView(
            message = state.message,
            cause = state.cause,
            onRetry = onRetry,
            modifier = modifier,
        )
        is LoadState.Success -> if (emptyCheck(state.data)) {
            Box(modifier = modifier, contentAlignment = Alignment.Center) { emptyContent() }
        } else {
            Box(modifier = modifier) { content(state.data) }
        }
    }
}

@Composable
private fun LoadingView(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorView(
    message: String,
    cause: Throwable?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.height(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        if (cause != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = cause.message ?: cause::class.simpleName ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            PrimaryButton(onClick = onRetry) { Text(stringResource("common_retry")) }
        }
    }
}
