package com.mqclient.mqui

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 通用「命令执行 + 异步状态轮询」工具：IBM MQ 的 START/STOP 类命令返回成功≠状态已变
 * （命令异步生效），需轮询真实状态，直到达到目标态或超时。通道启停、监听器启停、队列清空、
 * 消息删除等「命令返回≠结果生效」的场景都可用它统一处理。
 *
 * 流程：
 *  1. [command] 执行命令，返回 [CommandOutcome]（成功 / 失败 + 原因）；
 *  2. 若命令失败，整体返回 [PollOutcome.CommandFailed]，不进入轮询；
 *  3. 命令成功后按 [intervalMs] 周期性调用 [query] 取真实状态（返回 null 表示本轮暂不可判定，
 *     如缓存尚未包含该对象），一旦 [isTarget] 命中即返回 [PollOutcome.Reached]；
 *  4. 超过 [timeoutMs] 仍未命中返回 [PollOutcome.Timeout]（携带轮询期间最后一次查询错误，无则 null）；
 *  5. 轮询 / 查询抛异常不静默：记为错误并继续轮询；超时后若曾出错，随 [PollOutcome.Timeout] 返回。
 *
 * 全程在协程内用 [delay] 等待，不阻塞调用线程；支持取消 —— 切换连接 / 离开页面时取消所在协程即可
 * （取消会在下一轮查询或 delay 边界生效）。
 *
 * @param T 状态类型（如状态可读名 String，或带状态的快照）。
 * @param command 执行命令（如发送 MQSC），返回成功与否与错误信息。
 * @param query 轮询取真实状态；返回 null 表示本轮无法判定，继续下一轮。
 * @param isTarget 判定 [query] 返回的状态是否已是目标态。
 * @param intervalMs 轮询间隔（默认 1000ms）。
 * @param timeoutMs 最长等待（默认 30000ms）。
 */
suspend fun <T> pollUntil(
    command: suspend () -> CommandOutcome,
    query: suspend () -> T?,
    isTarget: (T) -> Boolean,
    intervalMs: Long = 1_000L,
    timeoutMs: Long = 30_000L,
): PollOutcome<T> {
    val cmd = command()
    if (!cmd.success) return PollOutcome.CommandFailed(cmd.errorMessage)
    val deadline = System.currentTimeMillis() + timeoutMs
    var pollError: String? = null
    while (true) {
        coroutineContext.ensureActive()
        // 显式 try/catch 而不是 `runCatching`：后者的 onFailure 会把 CancellationException
        // 一并吞掉（取消被当成「查询失败」继续轮询），与项目其他地方的纪律不一致
        //（JobScheduler / EventBus 都是「取消必须原样上抛」）。
        val status = try {
            query()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Throwable) {
            pollError = e.message ?: e::class.simpleName ?: "unknown"
            null
        }
        if (status != null && isTarget(status)) return PollOutcome.Reached(status)
        if (System.currentTimeMillis() >= deadline) return PollOutcome.Timeout(pollError)
        delay(intervalMs)
    }
}

/** 命令执行结果（供 [pollUntil] 的 [command] 返回）。 */
data class CommandOutcome(
    /** 命令是否成功（PCF 完成码 OK）。 */
    val success: Boolean,
    /** 失败时的可读原因（已含 MQRC 翻译等）。 */
    val errorMessage: String? = null,
)

/**
 * [pollUntil] 的整体结果。
 * @param T 状态类型。
 */
sealed interface PollOutcome<out T> {
    /** 命令执行失败（未进入轮询）。 */
    data class CommandFailed(val errorMessage: String?) : PollOutcome<Nothing>

    /** 轮询命中目标态，携带最后一次查到的真实状态。 */
    data class Reached<out T>(val status: T) : PollOutcome<T>

    /** 超时仍未命中目标态；[lastError] 为轮询期间最后一次查询错误（无则 null）。 */
    data class Timeout<out T>(val lastError: String?) : PollOutcome<T>
}
