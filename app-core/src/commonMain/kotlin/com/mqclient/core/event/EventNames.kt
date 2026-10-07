package com.mqclient.core.event

/**
 * 核心 / 跨模块通用的事件名常量。
 *
 * 命名约定 `<模块名>.<对象>.<动作>`：
 *  - **模块名**全局唯一（core / ui / ibm-mq / rabbitmq / ai …），前缀天然隔离模块：
 *    模块用 `onPrefix("ibm-mq.")` 就只收自己的事件，不需要总线知道有哪些模块；
 *  - **对象**是操作的对象（connection / scan / queue / conversation …）；
 *  - **动作**是发生了什么（added / deleted / completed / failed / changed …）。
 *
 * 因为名字是分段的字符串，`onSuffix(".scan.completed")` 能横跨所有模块监听同一个
 * 动作 —— 这是用字符串而不是 sealed class 换来的能力。
 *
 * 各业务模块的事件名放在**模块自己的**常量文件里（如 ibm-mq 模块的 `IbmMQEvents`），
 * 这里只放核心与跨模块通用的名字。新增事件 = 新增一行常量，总线零改动。
 */
object EventNames {

    /** 核心模块自己的事件前缀，等价于 `onPrefix("core.")`。 */
    const val PREFIX = "core."

    // ---- 核心事件 ----

    /** 应用已启动；订阅者都注册完、开始收集之后才发出（见 EventBus.awaitSubscribers）。 */
    const val APP_STARTED = "core.app.started"

    /** 设置已保存；payload: `config`(Config) / `languageChanged`(Boolean)。 */
    const val SETTINGS_CHANGED = "core.settings.changed"

    /** 界面语言切换；payload: `language`(Language)。 */
    const val LANGUAGE_CHANGED = "core.language.changed"

    /** 任意模块上报了错误；payload: `message`(String) / `moduleId`(String)。 */
    const val ERROR_OCCURRED = "core.error.occurred"

    // ---- 定时任务调度器事件（JobScheduler） ----

    /** 调度器开始执行某个任务；payload: `jobId`(String)。 */
    const val SCHEDULER_JOB_STARTED = "core.scheduler.job.started"

    /**
     * 任务执行成功；payload: `jobId`(String) / `durationMs`(Long)。
     *
     * ⚠️ 与 [SCHEDULER_JOB_STARTED] 一样属于高频事件：一个 5 秒的任务每分钟发 24 条，
     * 订阅方（调试面板之类）自己按 `jobId` 过滤，别把它当状态用。
     */
    const val SCHEDULER_JOB_COMPLETED = "core.scheduler.job.completed"

    /**
     * 任务执行失败；payload: `jobId`(String) / `message`(String) / `error`(String，异常类名)。
     *
     * 与 [SCHEDULER_ERROR] 描述的是同一次失败（前者是任务级生命周期，后者是调度器的
     * 统一错误口），订阅其中一个即可。
     */
    const val SCHEDULER_JOB_FAILED = "core.scheduler.job.failed"

    /** 调度器统一错误上报；payload 同 [SCHEDULER_JOB_FAILED]。 */
    const val SCHEDULER_ERROR = "core.scheduler.error"

    // ---- 连接通用事件（跨模块，各 MQ 模块共用） ----
    //
    // 三条事件都由 core 的 `ConnectionStore` 在**落盘成功后**发出（创建 / 编辑 / 删除
    // 共用一个写入路径，见 `ConnectionStore.saveAll`），`source = "core"`。
    // payload 统一为 `connectionId` / `type`（= `ConnectionInfo.serviceId`，也就是模块 id）
    // / `name`，模块按 `type` 过滤出属于自己的连接。
    //
    // 连接**状态**（已连接 / 连接失败）不走事件：模块直接调 `ConnectionStore.reportStatus()`
    // （连接数据的唯一入口，见 Connection.kt），界面订阅那份 StateFlow。

    /** 新建连接落盘成功后；payload: `connectionId` / `type` / `name`。 */
    const val CONNECTION_ADDED = "core.connection.added"

    /** 连接配置被编辑并落盘成功后；payload: `connectionId` / `type` / `name`。 */
    const val CONNECTION_UPDATED = "core.connection.updated"

    /** 连接删除落盘成功后；payload: `connectionId` / `type` / `name`。模块订阅它来清理自己的数据。 */
    const val CONNECTION_DELETED = "core.connection.deleted"

    /**
     * 连接快照已广播；订阅方（app-core 的 MqConnectionSnapshotHandler）把它写进
     * mq_connection 表，供 operation_history 关联。发出方是各 MQ 模块 —— 只有模块
     * 自己知道自己 options 里哪个键是逻辑容器（target）/ 用户名，所以 payload 由
     * 模块组装（见 ibm-mq 模块的 IbmMQConnectionSnapshotEmitter）。
     * payload: `connectionId` / `type` / `name` / `host` / `port` / `target` / `userName`。
     */
    const val MQ_CONNECTION_SNAPSHOT_CREATED = "core.mq.connection.snapshot.created"

    /**
     * 写操作命令已执行；订阅方（app-core 的 MqCommandHistoryHandler）把它写进
     * mq_command 表（写操作审计，只追加）。发出方是各 MQ 模块的命令执行咽喉点
     * （IBM MQ = MqscExecutor.executeSingle 经 MqCommandHistoryEmitter）——只记写操作，
     * DISPLAY 等只读命令不发本事件。payload 自包含，订阅者不需要再查任何数据：
     * `commandId`(记录 UUIDv7) / `connectionId` / `command`(展开后原文) / `response` /
     * `status`(SUCCESS|FAILED|CANCELLED) / `reasonCode` / `requestTime` /
     * `responseTime` / `durationMs`。
     */
    const val MQ_COMMAND_EXECUTED = "core.mq.command.executed"

    /**
     * 一次「MQ 对象 AI 分析问答」已结束（成功 / 失败 / 取消）；订阅方（app-core 的
     * MqAiConversationHandler）把它写进 mq_ai_conversation 表（对象维度审计，只追加）。
     * 发出方是 AI 面板（ai-ui 的 AiPanelState）——仅限从「询问 AI」入口发起的链路分析
     * 会话，普通对话不报本事件（那部分走 ai-service 的 chat_history）。payload 自包含，
     * 订阅者不需要再查任何数据：`conversationId`(记录 UUIDv7) / `connectionId` /
     * `objectType`(QUEUE|CHANNEL|LISTENER，可空) / `objectName`(可空) / `question`(原文) /
     * `contextJson`(链路上下文 JSON，可空) / `answer`(失败时为已流出部分，可空) /
     * `status`(SUCCESS|FAILED|CANCELLED) / `errorMessage`(可空) / `requestTime` /
     * `responseTime` / `durationMs`。
     */
    const val MQ_AI_CONVERSATION_COMPLETED = "core.mq.ai.conversation.completed"

    // AI 事件（`ai.*`）不在这里：契约常量跟着发出方走，现在在 ai-service 的
    // `AiEvents`（事件名）与 `AiPayloadKeys`（payload key）里 —— core 不再持有任何 AI 名字。
}

