package com.mqclient.aiservice

/**
 * AI 模块的事件契约：事件名 + payload key，**只在这里定义一次**。
 *
 * 放在模块内部而不是 app-core：新增 / 删除模块都不需要动事件总线，也不需要改
 * core 的 `EventNames` —— 与 ibm-mq 的 `IbmMQEvents` 同一约定。core 的 `EventNames`
 * 只留核心自产事件与跨模块通用契约（connection / scheduler / error）。
 *
 * 命名：`<模块名>.<对象>.<动作>`，本域的命名空间是 `ai.`。注意它与 SPI 描述模块的
 * id（`openai-compatible`，provider 维度，「哪一类 API」）不是一回事 —— 所以
 * `ServiceContext.onPrefix(moduleId)` 那种「收自己模块的事件」的写法在这里不适用，
 * 两端按下面的常量精确订阅。
 *
 * 生产端：ai-ui 的 AI 面板（[AI_CHAT_HISTORY_SAVE]）、本模块的
 * [AiConnectionRepository]（[AI_CONNECTION_SNAPSHOT_CREATED]）；消费端：本模块的
 * `AiChatHistoryHandler` / `AiConnectionSnapshotHandler`。两端引用同一份常量，
 * key 拼错不会再变成「静默丢一条审计记录」。
 */
object AiEvents {

    /** 本域事件的前缀。 */
    const val PREFIX = "ai."

    /**
     * AI 连接快照已创建。只在「新建连接」或「service_url 变化」（后者会废弃旧连接、
     * 换发新 connectionId 写入）后发出；改 model / token / name / 提示词不发。
     */
    const val AI_CONNECTION_SNAPSHOT_CREATED = "ai.connection.snapshot.created"

    /**
     * AI 对话已完成，请求落一条 chat_history 审计记录。成功 / 失败都发；
     * 链路分析被用户取消时也发（status = CANCELLED，reply = 已流出的部分）。
     */
    const val AI_CHAT_HISTORY_SAVE = "ai.chat.history.save"
}

/**
 * `ai.*` 事件的 payload key 常量；两个事件共用 [CONNECTION_ID]。
 *
 * 为什么要常量而不是裸字符串：生产端（ai-ui 的面板、本模块的仓储）与消费端（本模块的
 * handler）分处两个模块，key 拼错时 `event.getString("…")` 拿到 null、handler 直接
 * `?: return` —— 失败是**静默**的。引用同一份常量把这类错误挡在编译期。
 *
 * `AppEvent.payload` 是开放的 `Map<String, Any?>`：这里的常量是**约定**而不是类型，
 * 新增字段时两端要一起改。
 */
object AiPayloadKeys {

    /** 连接 id（两个事件共用）。 */
    const val CONNECTION_ID = "connectionId"

    // ---- ai.connection.snapshot.created ----

    /** service_url 变化时旧连接的 UUID；全新建为 null。 */
    const val PREV_ID = "prevId"

    /** 本次快照记下的接口地址。 */
    const val SERVICE_URL = "serviceUrl"

    // ---- ai.chat.history.save ----

    /** 模型名（`chat_history.model_name`）。 */
    const val MODEL_NAME = "modelName"

    /** 提问正文（含包装过的上下文前缀，与发给模型的完全一致）。 */
    const val QUESTION = "question"

    /** 回复正文；失败时为 null。 */
    const val REPLY = "reply"

    /** 总 token 数；服务端没给时为 0。 */
    const val TOKEN_COUNT = "tokenCount"

    /** 提示 token 数；服务端没给时为 0。 */
    const val PROMPT_TOKENS = "promptTokens"

    /** 回复 token 数；服务端没给时为 0。 */
    const val COMPLETION_TOKENS = "completionTokens"

    /** 提问时刻（epoch 毫秒）。 */
    const val CREATED_TIME = "createdTime"

    /** 首个分片到达的时刻；一个字都没流出来时为 null。 */
    const val STARTED_TIME = "startedTime"

    /** 流结束时刻。 */
    const val FINISHED_TIME = "finishedTime"

    /** `SUCCESS` / `FAILED`（取值见 `ChatHistoryRepository.STATUS_*`）。 */
    const val STATUS = "status"

    /** 失败原因；成功时为 null。 */
    const val ERROR_MESSAGE = "errorMessage"

    /** 服务端给的结束原因（`stop` / `length` …）。 */
    const val FINISH_REASON = "finishReason"

    // ---- 链路分析专用（普通聊天不传，落库为 NULL） ----

    /** 链路分析目标对象类型（QUEUE / CHANNEL / LISTENER）；普通聊天为 null。 */
    const val OBJECT_TYPE = "objectType"

    /** 链路分析目标对象名（如 ORDER.LOCAL.Q）；普通聊天为 null。 */
    const val OBJECT_NAME = "objectName"

    /** 链路分析发给模型的完整上下文 JSON；普通聊天为 null。 */
    const val CONTEXT_JSON = "contextJson"
}
