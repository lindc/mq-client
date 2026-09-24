package com.mqclient.mqui.state

/**
 * 通用「加载状态」抽象：把「还没开始 / 加载中 / 成功 / 失败」四态收拢到一个类型里，
 * 让所有异步列表（队列、通道、集群、属性面板…）共用同一套语义，避免各自散落
 * 的 `loading: Boolean` + `error: String?` 造成的「分不清加载中还是真没数据」。
 *
 * 纯数据、不依赖 Compose，放在 mq-ui 的接口层（commonMain），实现方直接持有。
 * 不引入新状态框架 —— 配合项目现有的 `remember { mutableStateOf }` 即可：
 *
 * ```kotlin
 * var state by remember(connectionId) { mutableStateOf<LoadState<List<X>>>(LoadState.Loading) }
 * ```
 *
 * 改造前请先确认持有方没有用 StateFlow / ViewModel 等 —— 若有，把本类型作为
 * StateFlow 的发射值即可，不要强行替换既有状态机制。
 */
sealed interface LoadState<out T> {
    /** 还没触发加载（可选占位态，多数页面初始直接进 [Loading]）。 */
    object Idle : LoadState<Nothing>

    /** 加载中：展示进度指示，不渲染数据 / 错误。 */
    object Loading : LoadState<Nothing>

    /** 加载成功，携带数据。 */
    data class Success<T>(val data: T) : LoadState<T>

    /**
     * 加载失败，携带人类可读的 [message]；[cause] 可选，仅用于调试态展示
     * （默认不展示堆栈，仅 `cause.message`）。
     *
     * 是否提供重试由调用方传 `onRetry` 决定，这里不额外加 `retryable` 字段
     * —— 当前所有失败场景都允许重试，没有「不可重试」需求，避免提前设计。
     */
    data class Error(val message: String, val cause: Throwable? = null) : LoadState<Nothing>
}
