package com.mqclient.aiui

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
import com.mqclient.aiservice.db.ChatHistory
import com.mqclient.aiservice.db.ChatHistoryRepository
import com.mqclient.core.AiConfig
import com.mqclient.core.AiConnection
import com.mqclient.core.ConfigStore
import com.mqclient.core.CoreModule
import com.mqclient.core.event.EventNames
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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

    // ---- 历史加载（来源：chat_history 表，按当前激活连接） ----

    /** 已经把哪个连接的历史灌进了 [messageList]；null 表示还没加载过。 */
    private var loadedConnectionId: String? = null

    /** 流式进行中切了连接：结束（成功/失败/取消）后补加载新连接的历史。 */
    private var pendingReload: Boolean = false

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
     * 这是「读-改-写」路径：盘上是整份 `config.toml`（连接列表也在里面），所以
     * 必须读到真实内容再写回 —— 用 [ConfigStore.loadConfig] 的话，配置一旦读不出来
     * 它会静默给出默认值，拿默认值去 copy 再落盘会把连接列表整段抹掉。
     *
     * 走 [ConfigStore.editConfig]（原子的读-改-写）而不是「load 再 save」两步：两步
     * 之间的窗口里 [com.mqclient.core.ConnectionStore] 的一次保存会把它整段覆盖。
     * 读不出来时 [ConfigStore.editConfig] 一个字节都不写，只改内存值，并把原因交给
     * [panelError]。
     */
    fun updateConfig(transform: (AiConfig) -> AiConfig) {
        val previousActiveId = activeConnection?.id
        val next = transform(config)
        config = next
        ConfigStore.editConfig { current -> Result.success(current.copy(ai = next)) }
            .onFailure { error ->
                panelError = "设置保存失败：${error.message ?: error.javaClass.simpleName}"
            }
        // 面板内切换连接：历史按连接走，切了人就换一份。
        if (activeConnection?.id != previousActiveId) requestHistoryReload()
    }

    /**
     * 外部（设置对话框）传来的新配置：只同步内存值，**不落盘** —— 盘上已经是新值了
     * （那条路径自己写过 config.toml）。激活连接被换掉的话历史同样要重载。
     */
    fun applyConfig(config: AiConfig) {
        val previousActiveId = activeConnection?.id
        this.config = config
        if (activeConnection?.id != previousActiveId) requestHistoryReload()
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
        // 落库的 question 是「含包装上下文」的 wire 版本（与发给模型的一致）。
        val question = userMsg.copy(content = buildContextPrefix(context)?.let { "$it\n\n$trimmed" } ?: trimmed)
        val historyForCall = messages + question

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
            // 落库用的时间戳：createdTime = 提问时刻；startedTime = 首片到达；
            // finishedTime = 流结束。三者构成一条审计记录的耗时画像。
            val createdAt = userMsg.timestamp
            var startedAt: Long? = null
            var finishedAt: Long? = null
            try {
                val producer = async {
                    try {
                        chat.streamReply(historyForCall, connection, context) { chunk -> chunks.trySend(chunk) }
                    } finally {
                        chunks.close()
                    }
                }
                for (chunk in chunks) {
                    if (startedAt == null) startedAt = System.currentTimeMillis()
                    appendToMessage(assistantMsg.id, chunk)
                }
                // 生产端的异常在这里浮上来（join 不会重抛，await 会）。
                val result = producer.await()
                finishedAt = System.currentTimeMillis()
                val reply = messageList.lastOrNull { it.id == assistantMsg.id }?.content.orEmpty()
                emitChatSaved(
                    connection = connection,
                    question = question.content,
                    reply = reply,
                    createdAt = createdAt,
                    startedAt = startedAt,
                    finishedAt = finishedAt,
                    status = ChatHistoryRepository.STATUS_SUCCESS,
                    errorMessage = null,
                    result = result,
                )
            } catch (cancellation: CancellationException) {
                // 取消不是失败，必须原样抛出（吞掉会让 scope 的取消失效）。
                // 用户主动停止的半截回复不进审计表 —— 审计记录以「完成」为单位。
                throw cancellation
            } catch (error: Throwable) {
                // 失败必须看得见：否则界面上只剩一个永远“…”的空气泡。未捕获异常逃出
                // 这里会直接把 scope 的 Job 弄失败，整个面板之后 launch 全部被取消。
                // 刻意不写进 messages —— 那会被下一轮当成 history 发给模型；失败只进
                // 审计表（FAILED 记录，见 emitChatSaved）。
                panelError = error.message ?: error.javaClass.simpleName
                emitChatSaved(
                    connection = connection,
                    question = question.content,
                    reply = null,
                    createdAt = createdAt,
                    startedAt = startedAt,
                    finishedAt = System.currentTimeMillis(),
                    status = ChatHistoryRepository.STATUS_FAILED,
                    errorMessage = error.message ?: error.javaClass.simpleName,
                    result = null,
                )
            } finally {
                streamingMessageId = null
                // 一个字都没流出来就撤掉空气泡：出错时提示条已经说明了原因；被取消时
                // 更不该留一条空回复 —— 下一轮会被当成 history 又发给模型。流了一半的
                // 要保留，那是用户点「停止」之前看到的。
                if (messageList.lastOrNull { it.id == assistantMsg.id }?.content.isNullOrEmpty()) {
                    messageList.removeAll { it.id == assistantMsg.id }
                }
                if (pendingReload) {
                    pendingReload = false
                    requestHistoryReload()
                }
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
     * 记录 👍/👎 评价。评价只活在**内存**里：chat_history 是追加式审计表（只插入
     * 不更新），没有评价的位置 —— 重启后评价丢失是计划内的取舍。
     */
    fun recordFeedback(messageId: String, useful: Boolean?) {
        val idx = messageList.indexOfLast { it.id == messageId }
        if (idx < 0) return
        val current = messageList[idx]
        if (current.feedback == useful) return
        // 只改这一条的下标：别的气泡不会因此重组。
        messageList[idx] = current.copy(feedback = useful)
    }

    /**
     * 清空对话：内存列表立即清；当前连接在 chat_history 表里的记录一并删除
     * （审计表只在这个**用户显式动作**和保留天数清理时才允许 DELETE）。
     */
    fun clearHistory() {
        messageList.clear()
        val connectionId = activeConnection?.id ?: return
        scope.launch {
            runCatching { ChatHistoryRepository.deleteByConnectionId(connectionId) }
                .onFailure { error ->
                    CoreModule.reportError(message = "清空 AI 对话失败", source = SOURCE_TAG, error = error)
                }
        }
    }

    // ---------------------------- 历史（DB） ----------------------------

    /**
     * 把当前激活连接的历史从 chat_history 表灌进面板（面板打开时与切换连接时调）。
     *
     *  - 流式中跳过：流出来的分片正在追加，重灌会把它冲掉；记 [pendingReload]，
     *    流结束的 finally 里补。
     *  - 加载期间用户又切了连接：按 [loadedConnectionId] 弃掉过期结果。
     *  - FAILED 记录只进审计，不进对话流（也不会被当 history 发给模型）。
     */
    fun requestHistoryReload() {
        val connectionId = activeConnection?.id
        if (connectionId == null) {
            loadedConnectionId = null
            messageList.clear()
            return
        }
        if (connectionId == loadedConnectionId) return
        if (isStreaming) {
            pendingReload = true
            return
        }
        loadedConnectionId = connectionId
        messageList.clear()
        scope.launch {
            val rows = runCatching {
                ChatHistoryRepository.getRecent(connectionId, limit = config.historyLimit.toLong())
            }.onFailure { error ->
                CoreModule.reportError(message = "AI 对话历史读取失败", source = SOURCE_TAG, error = error)
            }.getOrDefault(emptyList())
            // 加载期间又切了连接（或又发起了加载）：这批结果已经过期。
            if (loadedConnectionId != connectionId) return@launch
            messageList.addAll(rows.toAiMessages())
        }
    }

    /** 审计行 → 面板消息：按时间正序展开，question→USER、reply→ASSISTANT。 */
    private fun List<ChatHistory>.toAiMessages(): List<AiMessage> =
        asSequence()
            .filter { it.status == ChatHistoryRepository.STATUS_SUCCESS }
            .sortedWith(compareBy({ it.createdTime }, { it.id }))
            .flatMap { row ->
                buildList {
                    add(AiMessage(id = newId(), role = Role.USER, content = row.question.orEmpty(), timestamp = row.createdTime))
                    // reply 是跨模块生成的开放属性，smart cast 不可用 —— 先落局部变量。
                    val reply = row.reply
                    if (!reply.isNullOrEmpty()) {
                        add(
                            AiMessage(
                                id = newId(),
                                role = Role.ASSISTANT,
                                content = reply,
                                timestamp = row.finishedTime ?: row.startedTime ?: row.createdTime,
                            ),
                        )
                    }
                }
            }
            .toList()

    // ---------------------------- 审计落库事件 ----------------------------

    /**
     * 广播 `ai.chat.history.save`：AI 回复完成（成功或失败）后由事件处理器落
     * chat_history。发事件而不是直接写库，是为了解耦 —— 面板不关心表结构，
     * 将来审计消费方（统计/导出）也可以直接订阅同一事件。
     */
    private fun emitChatSaved(
        connection: AiConnection,
        question: String,
        reply: String?,
        createdAt: Long,
        startedAt: Long?,
        finishedAt: Long?,
        status: String,
        errorMessage: String?,
        result: AiStreamResult?,
    ) {
        // 总线未初始化（单测环境）时静默跳过：聊天不能因为审计缺席而受影响。
        CoreModule.eventBusOrNull?.emit(
            name = EventNames.AI_CHAT_HISTORY_SAVE,
            payload = mapOf<String, Any?>(
                "connectionId" to connection.id,
                "modelName" to connection.model,
                "question" to question,
                "reply" to reply,
                "tokenCount" to (result?.totalTokens ?: 0),
                "promptTokens" to (result?.promptTokens ?: 0),
                "completionTokens" to (result?.completionTokens ?: 0),
                "createdTime" to createdAt,
                "startedTime" to startedAt,
                "finishedTime" to finishedAt,
                "status" to status,
                "errorMessage" to errorMessage,
                "finishReason" to result?.finishReason,
            ),
            source = SOURCE_TAG,
        )
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
 * Compose entry point. Wires the persisted [AiConfig] into a stable
 * [AiPanelState] tied to the local coroutine scope; history comes from the
 * chat_history table via [AiPanelState.requestHistoryReload].
 */
@Composable
fun rememberAiPanelState(initialConfig: AiConfig = ConfigStore.loadConfig().ai): AiPanelState {
    val scope = rememberCoroutineScope()
    // 刻意不给 remember 带 key：带上 initialConfig 会在每次保存设置时重建 state ——
    // 正在流式的回复被遗弃在旧对象上（新 state 的 streamingMessageId 为空，界面看上去
    // 停止了、用户还能再点发送 → 两个流并发），聊天记录也会被重新读库覆盖。
    val state = remember {
        AiPanelState(
            initialConfig = initialConfig,
            initialMessages = emptyList(),
            chat = AdapterChatService(),
            scope = scope,
        )
    }
    // 历史从 chat_history 表加载（DB 访问走 withDriver 的单槽 IO 调度器，不占 UI 线程）。
    LaunchedEffect(Unit) { state.requestHistoryReload() }
    // 设置保存后把新配置同步进来（只赋值，不再写盘）；激活连接变了会顺带重载历史。
    LaunchedEffect(initialConfig) { state.applyConfig(initialConfig) }
    return state
}

private const val SOURCE_TAG = "ai-panel"