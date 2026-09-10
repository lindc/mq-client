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

    /** payload: `connectionId` / `type`(模块 id) / `name` */
    const val CONNECTION_ADDED = "core.connection.added"

    /** payload: `connectionId` / `type`。模块订阅它来清理自己的数据。 */
    const val CONNECTION_DELETED = "core.connection.deleted"
}
