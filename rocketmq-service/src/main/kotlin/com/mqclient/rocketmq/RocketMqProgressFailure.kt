package com.mqclient.rocketmq

import org.apache.rocketmq.client.exception.MQBrokerException
import org.apache.rocketmq.client.exception.MQClientException

/**
 * 消费进度（`examineConsumeStats`）失败的三件事：**归因、留证、给文案**。
 *
 * ## 为什么需要这个文件
 *
 * 现场：打开消费组属性 →「消费进度」Tab → 界面只说「无法获取该消费组的详细位点信息。
 * 消费进度查询失败，可点击重试」，用户无从判断是没消费过、ACL 还是别的问题。
 *
 * 根因是**类型被抹掉了**：`RocketMqAdminClient.ioCall` 把所有协议异常统一包成
 * `IllegalStateException`（以便 UI 直接显示中文），于是 `fetchGroupDetail` 里那句
 *
 * ```kotlin
 * val noRoute = (e is MQClientException && e.responseCode == 17) || ...
 * ```
 *
 * **永远为 false** —— 它拿到的是 `IllegalStateException`，不是协议异常。CODE 17 的
 * 识别是死代码，所有失败一律落进 `FAILED`，自然只剩一句通用文案。
 *
 * ## 实测（2026-10-29，chillbear RocketMQ 5.3.2 集群）
 *
 * ```
 * $ mqadmin consumerProgress -n rmq-node1:9876 -g CG_Normal
 * Caused by: MQClientException: CODE: 17  DESC: No topic route info in name server
 *            for the topic: %RETRY%CG_Normal
 *
 * $ mqadmin consumerProgress -n rmq-node1:9876 -g chillbear-test-group
 * Caused by: MQClientException: CODE: 206 DESC: Not found the consumer group consume
 *            stats, because return offset table is empty, maybe the consumer not online
 * ```
 *
 * 两种都是「组没有可读进度」的**正常状态**（没消费过 / 没上线过），不是故障 ——
 * 它们各自需要不同的解释，而不是一句「查询失败」。
 *
 * ## 判据
 *
 * 协议异常藏在包装异常的 `cause` 链里，所以按**链**找 responseCode，而不是只看最外层。
 * ACL 拒绝在 RocketMQ 里常常是 `MQBrokerException` + `SYSTEM_ERROR(1)`，光看 code
 * 分辨不出，只能靠报文里的 permit 措辞兜底（[ACL_HINTS]）。
 */

/**
 * 一次进度查询失败的可展示归因。
 *
 * @property state 供 UI 选文案的枚举
 * @property code 协议响应码（拿不到即 null）—— 只进日志与 FAILED 的明细文案
 * @property detail 面向用户的**原始**错误摘要；仅 [RocketMqProgressState.FAILED] 展示
 *   （17 / 206 / 权限各有专属说明，再甩一串原始码只会分散注意）
 */
internal data class ProgressFailure(
    val state: RocketMqProgressState,
    val code: Int?,
    val detail: String?,
)

/** CODE 17：`%RETRY%<group>` 在 NameServer 没有路由。 */
internal const val RC_TOPIC_NO_ROUTE = 17

/** CODE 206：offset 表为空（组从未消费 / 从未上线）。 */
internal const val RC_NO_CONSUME_STATS = 206

/**
 * ACL 拒绝的报文特征（RocketMQ 不为此给专属 responseCode，通常是 SYSTEM_ERROR(1)）。
 *
 * 刻意**不含**裸 "ACL" / "权限"：报文里带 group 名，一个叫 `acl-group` 的组会把
 * CODE 17/206 误判成权限问题。响应码先判，这两个词才轮得到。
 */
private val ACL_HINTS = listOf(
    "NoPermission",
    "no permission",
    "permission denied",
    "not permitted",
    "ACL 认证失败",
    "权限不足",
)

/**
 * 从任意异常归因：[RocketMqProgressState] + 响应码 + 明细。
 *
 * 纯函数（不碰日志 / 网络），因此可以直接测：见 `RocketMqProgressFailureTest`。
 */
internal fun classifyProgressFailure(error: Throwable): ProgressFailure {
    val code = rocketMqResponseCode(error)
    val message = errorMessageOf(error)
    val state = when {
        code == RC_TOPIC_NO_ROUTE -> RocketMqProgressState.RETRY_TOPIC_NO_ROUTE
        code == RC_NO_CONSUME_STATS -> RocketMqProgressState.NO_CONSUME_STATS
        ACL_HINTS.any { message?.contains(it, ignoreCase = true) == true } ->
            RocketMqProgressState.NO_PERMISSION
        else -> RocketMqProgressState.FAILED
    }
    return ProgressFailure(
        state = state,
        code = code,
        // 17 / 206 的 detail 留给日志；FAILED 才需要把它甩给用户。
        detail = message?.takeIf { state == RocketMqProgressState.FAILED },
    )
}

/**
 * 沿 `cause` 链找 RocketMQ 的响应码。
 *
 * 必须沿链找：`ioCall` 把协议异常包进 `IllegalStateException(cause = 原始异常)`，
 * 只判最外层就永远拿不到码 —— 这正是原来的 bug。
 */
internal fun rocketMqResponseCode(error: Throwable): Int? {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        val code = when (current) {
            is MQClientException -> current.responseCode
            is MQBrokerException -> current.responseCode
            else -> null
        }
        if (code != null) return code
        current = current.cause
        depth++
    }
    return null
}

/** 根因报文（沿链取最内层非空 message）；拿不到就退回最外层。 */
private fun errorMessageOf(error: Throwable): String? {
    var current: Throwable? = error
    var deepest: String? = null
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        current.message?.takeIf(String::isNotBlank)?.let { deepest = it }
        current = current.cause
        depth++
    }
    return deepest ?: error.message
}

/** 防病态自引用链（`cause` 成环）。 */
private const val MAX_CAUSE_DEPTH = 12
