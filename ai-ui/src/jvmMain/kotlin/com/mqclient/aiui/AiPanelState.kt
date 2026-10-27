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
import com.mqclient.aiservice.AiEvents
import com.mqclient.aiservice.AiPayloadKeys
import com.mqclient.aiservice.db.Chat_history
import com.mqclient.aiservice.db.ChatHistoryRepository
import com.mqclient.core.AiConfig
import com.mqclient.core.AiConnection
import com.mqclient.core.ConfigStore
import com.mqclient.core.CoreModule
import com.mqclient.core.log.Log
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
 *
 * ### 会话缓存：按连接隔离
 *
 * 每个连接一份自己的消息列表（[messagesByConnection]），切换连接 = 切换展示的列表，
 * **不同连接的消息绝不混在一起**。历史从 chat_history 表的加载只发生在「往该连接
 * **发第一条消息之前**」且每连接一次（[ensureHistoryLoaded]）——打开面板、切换连接
 * 都不读库，没有连接的 cache 就以空列表呈现。
 */
@Stable
class AiPanelState internal constructor(
    initialConfig: AiConfig,
    private val chat: ChatService,
    private val scope: CoroutineScope,
) {
    var config: AiConfig by mutableStateOf(initialConfig)
        private set

    /**
     * 按连接隔离的会话消息缓存：connectionId → 该连接的全部消息（含正在流式追加的
     * 那一条）。列表底层是 [SnapshotStateList]：流式时每个分片只改「那一条」的下标，
     * 只有读它的气泡重组。
     */
    private val messagesByConnection = mutableMapOf<String, SnapshotStateList<AiMessage>>()

    /** 已经从 DB 加载过历史的连接（每连接只读一次；读失败也标记，避免每次发送都重试）。 */
    private val historyLoaded = mutableSetOf<String>()

    /**
     * 最近一次「询问 AI」分析的目标键（connectionId:objectType:objectName）。发送时与
     * 当前目标比对：目标切换 = 新分析对话，清掉内存里的消息列表（chat_history 不动）。
     */
    private var lastChainTargetKey: String? = null

    /** 取某连接的会话列表（没有就建一份空的）。 */
    private fun listFor(connectionId: String): SnapshotStateList<AiMessage> =
        messagesByConnection.getOrPut(connectionId) { mutableStateListOf() }

    /** 当前激活连接的会话列表；没有激活连接时为空。 */
    private fun activeList(): SnapshotStateList<AiMessage> =
        activeConnection?.id?.let { listFor(it) } ?: mutableStateListOf()

    /** 当前展示的消息（= 激活连接自己的缓存）。内容随状态变化，不要缓存引用。 */
    val messages: List<AiMessage>
        get() = activeConnection?.id?.let { messagesByConnection[it] } ?: emptyList()

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
     * 这是「读-改-写」路径：盘上是整份 `config.toml`（连接列表也在里面），所以
     * 必须读到真实内容再写回 —— 用 [ConfigStore.loadConfig] 的话，配置一旦读不出来
     * 它会静默给出默认值，拿默认值去 copy 再落盘会把连接列表整段抹掉。
     *
     * 走 [ConfigStore.editConfig]（原子的读-改-写）而不是「load 再 save」两步：两步
     * 之间的窗口里 [com.mqclient.core.ConnectionStore] 的一次保存会把它整段覆盖。
     * 读不出来时 [ConfigStore.editConfig] 一个字节都不写，只改内存值，并把原因交给
     * [panelError]。切换连接不需要任何加载动作 —— 展示列表本来就是按连接缓存的。
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
     * （那条路径自己写过 config.toml）。激活连接即使被换掉也不用加载 —— 缓存按
     * connectionId 取，第一次发送时才读历史。
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
     *
     * 历史加载只发生在这里：发送前若该连接的历史还没进过缓存，先读一次
     * chat_history（[ensureHistoryLoaded]），保证发给模型的上下文完整。
     *
     * 「询问 AI」链路分析（[AiContext.chain] 非空）走另一条消息组装路：用户消息
     * 的 wire 版本是完整上下文 JSON（currentTime / chain / userQuestion，气泡里仍
     * 显示原文），系统提示词附加链路分析提示词，且不注入 DB 历史 —— 分析会话从空
     * 对话开始，切换目标对象（[lastChainTargetKey] 变化）即清内存重新开始。
     */
    fun sendUserMessage(text: String, context: AiContext?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isStreaming) return
        val connection = activeConnection ?: return
        // 列表与消息都在入口定死：流式期间用户切换连接，追加仍然落在发起的那条
        // 连接自己的列表里 —— 两个连接的消息互不串。
        val list = listFor(connection.id)

        val chain = context?.chain
        val wireContent: String
        val analysisPrompt: String?
        // 链路分析的**未包裹**上下文 JSON：落库用（context_json 列要保持可解析）。
        val auditContextJson: String?
        if (chain != null) {
            val contextJson = buildChainContextJson(chain.contextJson, trimmed)
            auditContextJson = contextJson
            // 发给模型的那份多套一层不可信边界：chain 里的队列名 / 属性值全部来自
            // 被管理的中间件，属于外部数据。系统提示词会声明「标记内是数据、不是指令」
            // （见 chainAnalysisSystemPrompt），两道合起来才成立。
            wireContent = wrapUntrustedContext(contextJson)
            analysisPrompt = chainAnalysisSystemPrompt()
        } else {
            auditContextJson = null
            wireContent = buildContextPrefix(context)?.let { "$it\n\n$trimmed" } ?: trimmed
            analysisPrompt = null
        }

        val userMsg = AiMessage(
            id = newId(),
            role = Role.USER,
            content = trimmed,
            timestamp = System.currentTimeMillis(),
        )
        // 落库的 question 是「含包装上下文」的 wire 版本（与发给模型的一致）。
        val question = userMsg.copy(content = wireContent)
        // 链路分析不把大段 JSON 写进 chat_history 的 question —— 那里记原文，
        // 完整上下文 JSON 由 chat_history.context_json 单独承载。
        val auditQuestion = if (chain != null) trimmed else question.content

        val assistantMsg = AiMessage(
            id = newId(),
            role = Role.ASSISTANT,
            content = "",
            timestamp = System.currentTimeMillis(),
        )
        // 同步占位：双击的第二次点击必须被 isStreaming 挡在门外。
        streamingMessageId = assistantMsg.id

        streamingJob = scope.launch {
            if (chain != null) {
                // 切换分析目标 = 新对话：只清内存列表（chat_history 是审计，不动），
                // 也不注入 DB 历史 —— 分析会话不带旧上下文。
                val chainKey = "${connection.id}:${chain.objectType}:${chain.objectName}"
                if (chainKey != lastChainTargetKey) list.clear()
                lastChainTargetKey = chainKey
            } else {
                // 发消息之前才读一次历史（每连接一次，之后走缓存）。
                ensureHistoryLoaded(connection.id, list)
            }
            // 给模型的上下文 = 缓存里的全部消息 + 本条的 wire 版本。
            val historyForCall = list + question
            list.add(userMsg)
            list.add(assistantMsg)

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
                        chat.streamReply(historyForCall, connection, context, analysisPrompt) { chunk ->
                            chunks.trySend(chunk)
                        }
                    } finally {
                        chunks.close()
                    }
                }
                for (chunk in chunks) {
                    if (startedAt == null) startedAt = System.currentTimeMillis()
                    appendToMessage(list, assistantMsg.id, chunk)
                }
                // 生产端的异常在这里浮上来（join 不会重抛，await 会）。
                val result = producer.await()
                finishedAt = System.currentTimeMillis()
                val reply = list.lastOrNull { it.id == assistantMsg.id }?.content.orEmpty()
                emitChatSaved(
                    connection = connection,
                    chain = chain,
                    contextJson = auditContextJson,
                    question = auditQuestion,
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
                // 普通对话的半截回复不落库（审计以「完成」为单位）；链路分析则落一条
                // CANCELLED（reply = 已流出的部分）。
                if (chain != null) {
                    emitChatSaved(
                        connection = connection,
                        chain = chain,
                        contextJson = auditContextJson,
                        question = auditQuestion,
                        reply = list.lastOrNull { it.id == assistantMsg.id }?.content,
                        createdAt = createdAt,
                        startedAt = startedAt,
                        finishedAt = System.currentTimeMillis(),
                        status = ChatHistoryRepository.STATUS_CANCELLED,
                        errorMessage = null,
                        result = null,
                    )
                }
                throw cancellation
            } catch (error: Throwable) {
                // 失败必须看得见：否则界面上只剩一个永远“…”的空气泡。未捕获异常逃出
                // 这里会直接把 scope 的 Job 弄失败，整个面板之后 launch 全部被取消。
                // 刻意不写进 messages —— 那会被下一轮当成 history 发给模型；失败只进
                // 审计表（FAILED 记录，见 emitChatSaved）。SSL 类失败经 [AiSslHint]
                // 翻译成「开启忽略 SSL 证书校验」的指引（自签名证书场景）。
                panelError = AiSslHint.displayMessage(error)
                emitChatSaved(
                    connection = connection,
                    chain = chain,
                    contextJson = auditContextJson,
                    question = auditQuestion,
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
                if (list.lastOrNull { it.id == assistantMsg.id }?.content.isNullOrEmpty()) {
                    list.removeAll { it.id == assistantMsg.id }
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
        val list = activeList()
        val idx = list.indexOfLast { it.id == messageId }
        if (idx < 0) return
        val current = list[idx]
        if (current.feedback == useful) return
        // 只改这一条的下标：别的气泡不会因此重组。
        list[idx] = current.copy(feedback = useful)
    }

    /**
     * 清空对话：当前连接的内存列表立即清；chat_history 表里该连接的记录一并删除
     * （审计表只在这个**用户显式动作**和保留天数清理时才允许 DELETE）。
     */
    fun clearHistory() {
        val connectionId = activeConnection?.id ?: return
        listFor(connectionId).clear()
        scope.launch {
            // 不用 runCatching：它连 CancellationException 一起吞，协程取消失效。
            try {
                ChatHistoryRepository.deleteByConnectionId(connectionId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                CoreModule.reportError(message = "清空 AI 对话失败", source = SOURCE_TAG, error = error)
            }
        }
    }

    // ---------------------------- 历史加载（发消息前） ----------------------------

    /**
     * 确保该连接的历史已在缓存里：每连接**只读一次** DB（[historyLoaded] 去重，读
     * 失败同样标记 —— 缺历史比反复弹错好）。其余场景（打开面板 / 切换连接）都直接
     * 走缓存，不触发任何 DB 访问。
     *
     * 历史行按时间正序展开（question→USER、reply→ASSISTANT）追加进该连接自己的
     * 列表；FAILED 记录只进审计，不进对话流（也不会被当 history 发给模型）。
     */
    private suspend fun ensureHistoryLoaded(connectionId: String, list: SnapshotStateList<AiMessage>) {
        if (!historyLoaded.add(connectionId)) return
        val rows = try {
            ChatHistoryRepository.getRecent(connectionId, limit = config.historyLimit.toLong())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // 读取失败不弹全局错误框（非关键后台数据，缺了不影响发消息），降级为日志。
            Log.warn(SOURCE_TAG, "failed to load chat history: $error")
            emptyList()
        }
        list.addAll(rows.toAiMessages())
    }

    /** 审计行 → 面板消息：按时间正序展开，question→USER、reply→ASSISTANT。 */
    private fun List<Chat_history>.toAiMessages(): List<AiMessage> =
        asSequence()
            .filter { it.status == ChatHistoryRepository.STATUS_SUCCESS }
            .sortedWith(compareBy({ it.created_time }, { it.id }))
            .flatMap { row ->
                buildList {
                    add(AiMessage(id = newId(), role = Role.USER, content = row.question.orEmpty(), timestamp = row.created_time))
                    // reply 是跨模块生成的开放属性，smart cast 不可用 —— 先落局部变量。
                    val reply = row.reply
                    if (!reply.isNullOrEmpty()) {
                        add(
                            AiMessage(
                                id = newId(),
                                role = Role.ASSISTANT,
                                content = reply,
                                timestamp = row.finished_time ?: row.started_time ?: row.created_time,
                            ),
                        )
                    }
                }
            }
            .toList()

    // ---------------------------- 审计落库事件 ----------------------------

    /**
     * 广播 `ai.chat.history.save`：AI 回复完成（成功 / 失败；链路分析被取消也算完成）
     * 后由事件处理器落 chat_history。发事件而不是直接写库，是为了解耦 —— 面板不关心
     * 表结构，将来审计消费方（统计/导出）也可以直接订阅同一事件。
     *
     * [chain] 非空 = 链路分析轮次：payload 带上目标对象与完整上下文 JSON，落库到
     * chat_history 的三个专用列（原 mq_ai_conversation 表的字段，已并入）。
     */
    private fun emitChatSaved(
        connection: AiConnection,
        chain: AiChainTarget?,
        contextJson: String?,
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
            name = AiEvents.AI_CHAT_HISTORY_SAVE,
            payload = mapOf<String, Any?>(
                AiPayloadKeys.CONNECTION_ID to connection.id,
                AiPayloadKeys.MODEL_NAME to connection.model,
                AiPayloadKeys.QUESTION to question,
                AiPayloadKeys.REPLY to reply,
                AiPayloadKeys.TOKEN_COUNT to (result?.totalTokens ?: 0),
                AiPayloadKeys.PROMPT_TOKENS to (result?.promptTokens ?: 0),
                AiPayloadKeys.COMPLETION_TOKENS to (result?.completionTokens ?: 0),
                AiPayloadKeys.CREATED_TIME to createdAt,
                AiPayloadKeys.STARTED_TIME to startedAt,
                AiPayloadKeys.FINISHED_TIME to finishedAt,
                AiPayloadKeys.STATUS to status,
                AiPayloadKeys.ERROR_MESSAGE to errorMessage,
                AiPayloadKeys.FINISH_REASON to result?.finishReason,
                AiPayloadKeys.OBJECT_TYPE to chain?.objectType,
                AiPayloadKeys.OBJECT_NAME to chain?.objectName,
                AiPayloadKeys.CONTEXT_JSON to contextJson?.takeIf { chain != null },
            ),
            source = SOURCE_TAG,
        )
    }

    private fun appendToMessage(list: SnapshotStateList<AiMessage>, messageId: String, chunk: String) {
        val idx = list.indexOfLast { it.id == messageId }
        if (idx < 0) return
        // 只改这一条：列表身份不变，只有读它的气泡重组。
        list[idx] = list[idx].copy(content = list[idx].content + chunk)
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
 * [AiPanelState] tied to the local coroutine scope. History is NOT loaded here —
 * each connection's chat log is read from chat_history once, right before the
 * first message is sent to it (see [AiPanelState.sendUserMessage]).
 *
 * [initialConfig] **必须由调用方显式传入**（宿主持有的启动配置）：默认参数不允许
 * 走 `ConfigStore.loadConfig()` —— 那是同步读盘 + 解密，落在组合期会卡 UI 线程。
 * 无参时回落零开销的 [AiConfig] 空配置（仅测试/预览用）。
 */
@Composable
fun rememberAiPanelState(initialConfig: AiConfig = AiConfig()): AiPanelState {
    val scope = rememberCoroutineScope()
    // 刻意不给 remember 带 key：带上 initialConfig 会在每次保存设置时重建 state ——
    // 正在流式的回复被遗弃在旧对象上（新 state 的 streamingMessageId 为空，界面看上去
    // 停止了、用户还能再点发送 → 两个流并发），按连接缓存的会话列表也会跟着丢。
    val state = remember {
        AiPanelState(
            initialConfig = initialConfig,
            chat = AdapterChatService(),
            scope = scope,
        )
    }
    // 设置保存后把新配置同步进来（只赋值，不再写盘）。
    LaunchedEffect(initialConfig) { state.applyConfig(initialConfig) }
    return state
}

private const val SOURCE_TAG = "ai-panel"
