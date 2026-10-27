package com.mqclient.aiui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.withArg

/**
 * Right-side AI panel. Composed of three stacked bands: header (title +
 * service-dropdown + close), scrollable message list, and input area.
 *
 * Width is fixed at 360 dp so the main content never gets squeezed off
 * screen by an over-eager side panel.
 *
 * 「询问 AI」链路分析（[AiContext.chain] 非空）时，header 下方多一条分析目标栏：
 * 「正在分析：{对象名}」+ 退出分析按钮 + 「查看发送内容」折叠区（完整 chain JSON）。
 */
@Composable
fun AiPanel(
    state: AiPanelState,
    currentContext: AiContext?,
    onDismiss: () -> Unit,
    onClearChainTarget: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.width(360.dp).fillMaxHeight()) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(
            modifier = Modifier.fillMaxHeight().fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                AiPanelHeader(
                    connections = state.connections,
                    activeConnectionId = state.activeConnection?.id,
                    context = currentContext,
                    onConnectionSelected = { picked -> state.updateConfig { it.copy(activeConnectionId = picked.id) } },
                    onDismiss = onDismiss,
                )
                // 忽略 SSL = 该连接的证书校验被关掉（可能被中间人劫持）。开关在设置页，
                // 但风险发生在**对话时**，所以这里常驻一条提示 —— 不做事后提醒。
                if (state.activeConnection?.ignoreSsl == true) {
                    IgnoreSslBanner()
                }
                currentContext?.chain?.let { chain ->
                    ChainAnalysisBar(
                        chain = chain,
                        onClearChainTarget = onClearChainTarget,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AiMessageList(
                    messages = state.messages,
                    streamingMessageId = state.streamingMessageId,
                    onFeedback = { id, useful -> state.recordFeedback(id, useful) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // 失败提示条：请求异常必须看得见（未捕获过的话面板会直接无响应）。
                state.panelError?.let { message ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(onClick = { state.dismissError() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AiInputArea(
                    isStreaming = state.isStreaming,
                    context = currentContext,
                    onSend = { text -> state.sendUserMessage(text, currentContext) },
                    onCancel = { state.cancelStreaming() },
                )
            }
        }
    }
}

/**
 * 忽略 SSL 的常驻警告条：该连接的证书校验已被用户关掉。
 *
 * 用 `errorContainer`（而不是 secondaryContainer 那种信息色）——这是**安全降级**，
 * 不是配置提示；颜色与面板错误条同一档，用户扫一眼就知道有风险。
 */
@Composable
private fun IgnoreSslBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource("ai_ignore_ssl_warning"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 「正在分析：{目标}」栏：AutoAwesome 图标 + 目标名 + 退出分析（✕）。下方可折叠的
 * 「查看发送内容」展示本次分析携带的 chain JSON（发起时点构建，与发送给模型的一致，
 * 仅 userQuestion 每条消息不同）——给用户核对「AI 到底看到了什么」。
 */
@Composable
private fun ChainAnalysisBar(chain: AiChainTarget, onClearChainTarget: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource("ai_chain_analyzing").withArg(chain.targetLabel),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(onClick = onClearChainTarget) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        var showContext by remember { mutableStateOf(false) }
        SecondaryButton(
            onClick = { showContext = !showContext },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            Text(
                text = if (showContext) stringResource("ai_chain_hide_context") else stringResource("ai_chain_show_context"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        AnimatedVisibility(visible = showContext) {
            Text(
                text = chain.contextJson,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .selectable(true) {},
            )
        }
    }
}