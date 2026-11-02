package com.mqclient.core.scheduler

/**
 * 一个定时任务的定义：**做什么**（[action]）+ **多久做一次**（[intervalMs]）。
 *
 * 这里只有「怎么跑」的参数，没有「跑到哪一步了」的状态 —— 进度、上次结果这类东西
 * 属于任务自己的业务（模块自己记，或通过事件总线广播），调度器不替它们记。
 *
 * 关于两个「首次执行」参数的关系：
 *  - [runOnStart] = `true` → 注册后**立即**执行一次，[initialDelayMs] 被忽略
 *    （既要求立刻跑又要求先等一会儿是矛盾的，以「立刻」为准）；
 *  - [runOnStart] = `false` → 首次执行等 [initialDelayMs]；它没给（0）时等一个完整
 *    的 [intervalMs]，也就是「先等一轮再跑」，避免所有任务在启动瞬间一起扑上去。
 *
 * 之后的每一轮都是**固定延迟**（上一轮跑完再等 [intervalMs]），所以同一个任务永远
 * 不会和自己重叠 —— 扫描类任务最怕的就是上一轮还没扫完下一轮又开跑。
 */
data class ScheduledJob(
    /**
     * 任务唯一标识，格式 `<模块>.<连接>.<任务类型>`（如 `ibm-mq.conn-1.scan.messages`）。
     *
     * 它同时是取消任务用的键：`ibm-mq.<connectionId>.` 前缀一次就能收掉某个连接的
     * 全部任务（见 [JobScheduler.cancelByPrefix]）。用 [idFor] 拼比手写字符串稳妥。
     */
    val id: String,

    /** 执行间隔（毫秒），必须为正。 */
    val intervalMs: Long,

    /** 首次执行前的延迟（毫秒），仅当 [runOnStart] 为 false 时生效。 */
    val initialDelayMs: Long = 0,

    /** 是否在注册后立即执行一次（不等 [initialDelayMs]）。 */
    val runOnStart: Boolean = false,

    /** 任务逻辑；异常由 [JobScheduler] 兜住，不会影响别的任务。 */
    val action: suspend () -> Unit,
) {

    init {
        require(id.isNotBlank()) { "ScheduledJob.id must not be blank: it is also the key used to cancel the job" }
        require(intervalMs > 0L) { "ScheduledJob('$id').intervalMs must be positive, otherwise the job busy-waits" }
        require(initialDelayMs >= 0L) { "ScheduledJob('$id').initialDelayMs must not be negative" }
    }

    companion object {

        /**
         * 按约定拼任务 ID：`<模块>.<连接>.<任务类型>`。
         *
         * [moduleId] 用 `ServiceAdapter.id`（`ibm-mq`），[connectionId] 是那条连接的 id，
         * [taskType] 是模块自定义的任务类型（`scan.messages`）。
         */
        fun idFor(moduleId: String, connectionId: String, taskType: String): String =
            "$moduleId.$connectionId.$taskType"

        /**
         * 拼「这个连接的全部任务」的前缀，配合 [JobScheduler.cancelByPrefix] 使用。
         *
         * 结尾的点不能省：`ibm-mq.c1.` 是精确匹配到连接 `c1`，而 `ibm-mq.c1` 会连
         * `ibm-mq.c10.*` 一起收走。
         */
        fun prefixFor(moduleId: String, connectionId: String): String = "$moduleId.$connectionId."
    }
}
