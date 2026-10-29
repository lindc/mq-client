package com.mqclient.kafka

import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.apache.kafka.common.KafkaFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * `KafkaFuture` 的挂起等待（不占线程）。
 *
 * ## 为什么不能继续用 `future.get(timeout, unit)`
 *
 * `KafkaFuture.get` 是**阻塞**调用，而且**不可被协程取消**：
 *  - 它把调用线程钉死到结果返回为止 —— 大集群上 N 个 group 各自 `get` 一次，
 *    就得占着 N 个线程排队；`Dispatchers.IO` 的 64 个线程被吃满之后，同一轮扫描
 *    里其它请求（brokers / topics）只能干等；
 *  - 包在外面的 `withTimeout(...)` 这时是个**假守卫**：超时确实会抛
 *    `TimeoutCancellationException`，但线程仍然卡在 `get` 里直到它自己的超时到点，
 *    于是「3 秒超时」的代码实际跑了 30 秒，协程池继续被占。
 *
 * [toCompletionStage] 是 Kafka 官方给的异步出口：注册回调，协程挂起，线程交还。
 * 超时交给 `withTimeout` 管，是真的能打断。
 *
 * 放在 `*-service` 的协议查询层内部（`internal`）：这是实现细节，UI 不该看见。
 */

/** 挂起等待该 future 完成；失败时抛出**解包后**的根因（去掉 Completion 包装）。 */
internal suspend fun <T> KafkaFuture<T>.awaitKafka(): T = suspendCancellableCoroutine { cont ->
    toCompletionStage().whenComplete { value, error ->
        // 协程已被取消（外层 withTimeout / 用户操作打断）：回调照常触发，但不再 resume。
        if (!cont.isActive) return@whenComplete
        if (error == null) {
            cont.resume(value)
        } else {
            cont.resumeWithException(unwrapKafkaCompletion(error))
        }
    }
}

/** [awaitKafka] + 超时：超时抛 `TimeoutCancellationException`（调用方按「跳过这一项」处理）。 */
internal suspend fun <T> KafkaFuture<T>.awaitKafka(timeoutMs: Long): T =
    withTimeout(timeoutMs) { awaitKafka() }

/**
 * 去掉 `CompletionException` / `ExecutionException` 的包装，露出 broker 给的真错误。
 *
 * 为什么必须解：`UnknownMemberIdException` / `GroupIdNotFoundException` /
 * `GroupAuthorizationException` 这些会被包两层，UI 侧的分类逻辑（`kafkaErrorText`）
 * 只认根因类型 —— 不拆就一律落到「未知错误」，用户看不出是权限还是组没了。
 */
private fun unwrapKafkaCompletion(error: Throwable): Throwable {
    var current = error
    while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
        current = current.cause!!
    }
    return current
}
