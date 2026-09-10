package com.mqclient.aipanel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.mqclient.core.AiConfig
import com.mqclient.core.AiProvider
import com.mqclient.core.AiProviderRegistry
import com.mqclient.core.ConfigStore
import com.mqclient.core.DEFAULT_SYSTEM_PROMPT
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Single source of truth for the AI panel.
 *
 * Pulls the registered [AiProvider] list from the SPI registry and merges
 * in the user-configured overrides from [AiConfig.providerConfigs]. The
 * [activeProvider] is whatever provider matches [AiConfig.activeProviderId]
 * (or the first registered one if nothing's set).
 */
@Stable
class AiPanelState internal constructor(
    initialConfig: AiConfig,
    initialMessages: List<AiMessage>,
    private val chat: ChatService,
    private val scope: CoroutineScope,
) {
    var config: AiConfig by mutableStateOf(initialConfig)
        private set

    var messages: List<AiMessage> by mutableStateOf(initialMessages)
        private set

    /** Id of the assistant message currently being streamed, or null. */
    var streamingMessageId: String? by mutableStateOf(null)
        private set

    /** 上一次失败的说明（请求异常等）；界面提示条读取后调 [dismissError]。 */
    var panelError: String? by mutableStateOf(null)
        private set

    val isStreaming: Boolean get() = streamingMessageId != null

    /** All registered AI providers (from SPI). Recomputed when [AiProviderRegistry] changes. */
    val providers: List<AiProvider> = AiProviderRegistry.all

    /**
     * The provider the user picked via [AiConfig.activeProviderId], or the
     * first registered one if nothing's set. Computed on every read so it
     * stays in lockstep with [config] after [updateConfig].
     */
    val activeProvider: AiProvider?
        get() = providers.firstOrNull { it.id == config.activeProviderId }
            ?: providers.firstOrNull()

    /**
     * The override (URL/token/model/prompt) for the active provider, or
     * null when the user hasn't customised it yet — the runtime falls back
     * to the provider's defaults.
     */
    val activeProviderOverride: com.mqclient.core.AiProviderConfig?
        get() = config.providerConfigs.firstOrNull { it.providerId == activeProvider?.id }

    val systemPrompt: String
        get() = activeProviderOverride?.systemPromptBase64?.let(::decodePrompt) ?: DEFAULT_SYSTEM_PROMPT

    fun updateConfig(transform: (AiConfig) -> AiConfig) {
        val next = transform(config)
        config = next
        ConfigStore.saveConfig(ConfigStore.loadConfig().copy(ai = next))
    }

    /**
     * 外部（设置对话框）传来的新配置：只同步内存值，**不落盘** —— 盘上已经是新值了
     * （那条路径自己写过 config.json）。
     */
    fun applyConfig(config: AiConfig) {
        this.config = config
    }

    /** 关闭失败提示条。 */
    fun dismissError() {
        panelError = null
    }

    /**
     * Sends [text] as a user message, then kicks off a streaming reply.
     * No-op if [text] is blank, a previous turn is still streaming, or no
     * provider has been configured yet.
     */
    fun sendUserMessage(text: String, context: AiContext?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isStreaming) return
        if (activeProvider == null) return

        val userMsg = AiMessage(
            id = newId(),
            role = Role.USER,
            content = trimmed,
            timestamp = System.currentTimeMillis(),
        )

        val contextPrefix = buildContextPrefix(context)
        val wireContent = if (contextPrefix != null) "$contextPrefix\n\n$trimmed" else trimmed
        val historyForCall = messages + userMsg.copy(content = wireContent)

        val assistantMsg = AiMessage(
            id = newId(),
            role = Role.ASSISTANT,
            content = "",
            timestamp = System.currentTimeMillis(),
        )
        messages = messages + userMsg + assistantMsg
        streamingMessageId = assistantMsg.id

        scope.launch {
            try {
                chat.streamReply(historyForCall, config, context) { chunk ->
                    appendToMessage(assistantMsg.id, chunk)
                }
            } catch (cancellation: CancellationException) {
                // 取消不是失败，必须原样抛出（吞掉会让 scope 的取消失效）。
                throw cancellation
            } catch (error: Throwable) {
                // 失败必须看得见：否则界面上只剩一个永远“…”的空气泡。未捕获异常逃出
                // 这里会直接把 scope 的 Job 弄失败，整个面板之后 launch 全部被取消。
                // 刻意不写进 messages —— 那会被 AiStore 落盘、下一轮还会当 history 发给模型。
                panelError = error.message ?: error.javaClass.simpleName
                if (messages.lastOrNull { it.id == assistantMsg.id }?.content.isNullOrEmpty()) {
                    // 一个字都没流出来：撤掉空气泡，提示条已经说明了原因。
                    messages = messages.filterNot { it.id == assistantMsg.id }
                }
            } finally {
                streamingMessageId = null
                AiStore.saveMessages(messages)
            }
        }
    }

    /**
     * 记录 👍/👎 评价；[useful] 为 null 表示撤销评价。评价挂在消息上，随
     * [AiStore.saveMessages] 一起落盘。
     */
    fun recordFeedback(messageId: String, useful: Boolean?) {
        val idx = messages.indexOfLast { it.id == messageId }
        if (idx < 0) return
        val current = messages[idx]
        if (current.feedback == useful) return
        messages = messages.toMutableList().also { it[idx] = current.copy(feedback = useful) }
    }

    /** Drops every message and clears the on-disk log. */
    fun clearHistory() {
        messages = emptyList()
        AiStore.saveMessages(emptyList())
    }

    private fun appendToMessage(messageId: String, chunk: String) {
        val mutable = messages.toMutableList()
        val idx = mutable.indexOfLast { it.id == messageId }
        if (idx < 0) return
        mutable[idx] = mutable[idx].copy(content = mutable[idx].content + chunk)
        messages = mutable
    }

    /** Builds a `[CONTEXT] k=v =` prefix from the current selection, or null if nothing to attach. */
    private fun buildContextPrefix(context: AiContext?): String? {
        if (context == null) return null
        val parts = mutableListOf<String>()
        context.connectionName?.let { parts += "connection=$it" }
        context.serviceId?.let { parts += "service=$it" }
        context.objectType?.let { parts += "objectType=$it" }
        if (parts.isEmpty()) return null
        return "[CONTEXT] ${parts.joinToString(" ")}"
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun decodePrompt(b64: String): String =
        try {
            if (b64.isBlank()) DEFAULT_SYSTEM_PROMPT
            else String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
        } catch (_: Throwable) {
            DEFAULT_SYSTEM_PROMPT
        }

    companion object {
        /** Encode any user-entered prompt text into the Base64 storage form. */
        fun encodePrompt(plain: String): String =
            java.util.Base64.getEncoder().encodeToString(plain.toByteArray(Charsets.UTF_8))
    }
}

/**
 * Compose entry point. Loads the chat log off disk (sliced to
 * [AiConfig.historyLimit] most-recent messages) and wires the persisted
 * [AiConfig] into a stable [AiPanelState] tied to the local coroutine scope.
 */
@Composable
fun rememberAiPanelState(initialConfig: AiConfig = ConfigStore.loadConfig().ai): AiPanelState {
    val scope = rememberCoroutineScope()
    // 刻意不给 remember 带 key：带上 initialConfig 会在每次保存设置时重建 state ——
    // 正在流式的回复被遗弃在旧对象上（新 state 的 streamingMessageId 为空，界面看上去
    // 停止了、用户还能再点发送 → 两个流并发），聊天记录也会被重新读盘覆盖。
    val state = remember {
        AiPanelState(
            initialConfig = initialConfig,
            initialMessages = AiStore.loadMessages(initialConfig.historyLimit),
            chat = MockChatService(),
            scope = scope,
        )
    }
    // 设置保存后把新配置同步进来（只赋值，不再写盘）。
    LaunchedEffect(initialConfig) { state.applyConfig(initialConfig) }
    return state
}