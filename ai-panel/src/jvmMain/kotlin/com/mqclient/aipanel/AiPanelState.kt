package com.mqclient.aipanel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.mqclient.core.AiConfig
import com.mqclient.core.AiConnection
import com.mqclient.core.ConfigStore
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the AI panel.
 *
 * The dropdown lists the enabled entries of [AiConfig.connections]; the
 * [activeConnection] is whatever connection matches [AiConfig.activeConnectionId]
 * (or the first enabled one if nothing's set).
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

    /**
     * 当前会话的全部消息（含正在流式追加的那一条）。
     *
     * 底层是 [SnapshotStateList]：流式时每个分片只改「那一条」的下标，读它的气泡才会
     * 重组 —— 之前每个分片都 `messages.toMutableList()` 复制整段历史再整体替换，
     * 整棵消息列表（每个气泡）都会被重算。
     */
    private val messageList: SnapshotStateList<AiMessage> = mutableStateListOf<AiMessage>().apply {
        addAll(initialMessages)
    }

    /** 消息的只读视图。内容随状态变化，不要把它缓存下来。 */
    val messages: List<AiMessage> get() = messageList

    /** Id of the assistant message currently being streamed, or null. */
    var streamingMessageId: String? by mutableStateOf(null)
        private set

    /**
     * 正在流式回复的那个协程。必须留着句柄，否则用户只能干等 —— 界面点不到「停止」。
     *
     * 结束（正常 / 出错 / 被取消）之后它不会被清成 null，指向一个已完成的 Job；
     * 对已完成的 Job 调 [Job.cancel] 是无副作用的空操作，下一次 [sendUserMessage]
     * 会把它换掉，所以不需要（也不该在协程自己的 finally 里）抢着清空 —— 那会和
     * `launch` 之后的赋值竞争。
     */
    private var streamingJob: Job? = null

    /** 上一次失败的说明（请求异常等）；界面提示条读取后调 [dismissError]。 */
    var panelError: String? by mutableStateOf(null)
        private set

    val isStreaming: Boolean get() = streamingMessageId != null

    /** 面板连接下拉的候选：配置里启用的连接。 */
    val connections: List<AiConnection> get() = config.connections.filter { it.enabled }

    /**
     * The connection the user picked via [AiConfig.activeConnectionId], or the
     * first enabled one if nothing's set (or the picked one was deleted /
     * disabled). Computed on every read so it stays in lockstep with [config]
     * after [updateConfig].
     */
    val activeConnection: AiConnection?
        get() = connections.firstOrNull { it.id == config.activeConnectionId }
            ?: connections.firstOrNull()

    /**
     * 改一项 AI 设置并落盘。
     *
     * 这是「读-改-写」路径：盘上是整份 `config.json`（连接列表也在里面），所以
     * 必须读到真实内容再写回 —— 用 [ConfigStore.loadConfig] 的话，配置一旦读不出来
     * 它会静默给出默认值，拿默认值去 copy 再落盘会把连接列表整段抹掉。
     *
     * 走 [ConfigStore.editConfig]（原子的读-改-写）而不是「load 再 save」两步：两步
     * 之间的窗口里 [com.mqclient.core.ConnectionStore] 的一次保存会把它整段覆盖。
     * 读不出来时 [ConfigStore.editConfig] 一个字节都不写，只改内存值，并把原因交给
     * [panelError]。
     */
    fun updateConfig(transform: (AiConfig) -> AiConfig) {
        val next = transform(config)
        config = next
        ConfigStore.editConfig { current -> Result.success(current.copy(ai = next)) }
            .onFailure { error ->
                panelError = "设置保存失败：${error.message ?: error.javaClass.simpleName}"
            }
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
        val connection = activeConnection ?: return

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
        messageList.add(userMsg)
        messageList.add(assistantMsg)
        streamingMessageId = assistantMsg.id

        streamingJob = scope.launch {
            // onChunk 可能来自任意线程（真实 provider 会从 HTTP/SSE 回调），而 Compose
            // 状态只能在面板自己的调度器上写。用一条无界 channel 把分片转进本协程里串行
            // 追加：顺序天然保真，写状态的一定是面板的调度器。
            val chunks = Channel<String>(Channel.UNLIMITED)
            try {
                val producer = async {
                    try {
                        chat.streamReply(historyForCall, connection, context) { chunk -> chunks.trySend(chunk) }
                    } finally {
                        chunks.close()
                    }
                }
                for (chunk in chunks) {
                    appendToMessage(assistantMsg.id, chunk)
                }
                // 生产端的异常在这里浮上来（join 不会重抛，await 会）。
                producer.await()
            } catch (cancellation: CancellationException) {
                // 取消不是失败，必须原样抛出（吞掉会让 scope 的取消失效）。
                throw cancellation
            } catch (error: Throwable) {
                // 失败必须看得见：否则界面上只剩一个永远“…”的空气泡。未捕获异常逃出
                // 这里会直接把 scope 的 Job 弄失败，整个面板之后 launch 全部被取消。
                // 刻意不写进 messages —— 那会被 AiStore 落盘、下一轮还会当 history 发给模型。
                panelError = error.message ?: error.javaClass.simpleName
            } finally {
                streamingMessageId = null
                // 一个字都没流出来就撤掉空气泡：出错时提示条已经说明了原因；被取消时
                // 更不该留一条空回复 —— 它会被 AiStore 落盘，下一轮当成 history 又发给
                // 模型。流了一半的要保留，那是用户点「停止」之前看到的。
                if (messageList.lastOrNull { it.id == assistantMsg.id }?.content.isNullOrEmpty()) {
                    messageList.removeAll { it.id == assistantMsg.id }
                }
                AiStore.saveMessages(messages)
            }
        }
    }

    /**
     * 停止正在流式生成的回复。没有在流式时是空操作。
     *
     * 取消会顺着 [Job.cancel] 传到 `chat.streamReply`，HTTP/SSE 连接随之关闭；
     * 已经流出来的那部分**保留**在气泡里（用户看到的就是他拿到的），
     * 收尾与落盘由 [sendUserMessage] 的 `finally` 完成。
     */
    fun cancelStreaming() {
        streamingJob?.cancel()
        streamingJob = null
    }

    /**
     * 记录 👍/👎 评价；[useful] 为 null 表示撤销评价。评价挂在消息上，随
     * [AiStore.saveMessages] 一起落盘。
     */
    fun recordFeedback(messageId: String, useful: Boolean?) {
        val idx = messageList.indexOfLast { it.id == messageId }
        if (idx < 0) return
        val current = messageList[idx]
        if (current.feedback == useful) return
        // 只改这一条的下标：别的气泡不会因此重组。
        messageList[idx] = current.copy(feedback = useful)
    }

    /** Drops every message and clears the on-disk log. */
    fun clearHistory() {
        messageList.clear()
        AiStore.saveMessages(emptyList())
    }

    /**
     * 把磁盘上的历史灌回来（由 [rememberAiPanelState] 在 IO 调度器上读完再调）。
     *
     * 只在面板还是空的时候接受：读盘那一小段时间里用户可能已经发了第一条消息 ——
     * 那次会话不该被旧记录盖掉（流式进行中同理）。
     */
    internal fun restoreHistory(loaded: List<AiMessage>) {
        if (messageList.isNotEmpty() || isStreaming) return
        messageList.addAll(loaded)
    }

    private fun appendToMessage(messageId: String, chunk: String) {
        val idx = messageList.indexOfLast { it.id == messageId }
        if (idx < 0) return
        // 只改这一条：列表身份不变，只有读它的气泡重组。
        messageList[idx] = messageList[idx].copy(content = messageList[idx].content + chunk)
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
            // 历史在下面的 effect 里读：`remember { }` 在组合期间同步执行，直接
            // readText() 会卡在组合（= UI 线程）上。
            initialMessages = emptyList(),
            chat = AdapterChatService(),
            scope = scope,
        )
    }
    // 读盘放在 IO，读完再灌进 state：面板先以空列表渲染，不阻塞首帧。
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { AiStore.loadMessages(initialConfig.historyLimit) }
        state.restoreHistory(loaded)
    }
    // 设置保存后把新配置同步进来（只赋值，不再写盘）。
    LaunchedEffect(initialConfig) { state.applyConfig(initialConfig) }
    return state
}