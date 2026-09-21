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

    // ---- AI 事件 ----
    //
    // 按命名约定这些名字本该放 AI 模块自己的常量文件，但 AI 连接的数据层
    // （AiConnectionRepository）在 core 里、是快照事件的发出方，依赖方向不允许
    // core 反向引用 ai-panel —— 常量跟着发出方走。
    // 消费方是 ai-panel 的落库订阅（ai_connection / chat_history 两张表，SQLite）。

    /**
     * AI 连接快照已创建。只在「新建连接」或「service_url 变化」（后者会废弃旧连接、
     * 换发新 connectionId 写入）后发出；改 model / token / name / 提示词不发。
     * payload: `connectionId`(String) / `prevId`(String?，url 变化时为旧连接的 UUID，
     * 全新建为 null) / `serviceUrl`(String)。
     */
    const val AI_CONNECTION_SNAPSHOT_CREATED = "ai.connection.snapshot.created"

    /**
     * AI 对话已完成（成功或失败），请求落一条 chat_history 审计记录。
     * payload: `connectionId`(String) / `modelName`(String) / `question`(String) /
     * `reply`(String?) / `tokenCount`(Int) / `promptTokens`(Int) / `completionTokens`(Int) /
     * `createdTime`(Long) / `startedTime`(Long?) / `finishedTime`(Long?) /
     * `status`(String: SUCCESS|FAILED) / `errorMessage`(String?) / `finishReason`(String?)。
     */
    const val AI_CHAT_HISTORY_SAVE = "ai.chat.history.save"
}
