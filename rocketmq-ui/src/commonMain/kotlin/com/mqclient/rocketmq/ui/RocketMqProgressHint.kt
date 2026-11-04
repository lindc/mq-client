package com.mqclient.rocketmq.ui

import com.mqclient.rocketmq.RocketMqGroupDetailSnapshot
import com.mqclient.rocketmq.RocketMqProgressState

/**
 * 「消费进度」不可用时，到底该说哪句话。
 *
 * ## 为什么需要分层
 *
 * 现场反馈：提示只说「无法获取该消费组的详细位点信息」，看不出到底是
 * 「这个组根本不存在」「组存在但没人消费过」「没权限」还是「超时」。
 *
 * 而这些答案其实**已经在手上**了 —— 详情查询同时拿到了三样东西：
 *  - [RocketMqGroupDetailSnapshot.groupMissing]：broker 上有没有这条订阅组配置；
 *  - [RocketMqGroupDetailSnapshot.memberCount]：有没有在线消费者；
 *  - [RocketMqGroupDetailSnapshot.progressState]：进度查询失败的**归因**。
 *
 * 把它们组合起来，就能说出「哪个环节缺东西」，而不是一句笼统的失败。组合规则
 * 收在这个纯函数里（不碰 Compose、不碰 i18n 查表），因此可以直接断言：
 * 同一个 CODE 17，在「组不存在」和「组存在但没人消费」两种情形下必须给出
 * **不同**的解释。
 *
 * 优先级（先排除最根本的问题）：
 *  1. 组在 broker 上不存在 → 先说名字/删除，其余都无意义；
 *  2. 权限 → 账号问题，与组状态无关；
 *  3. 组存在但从未有消费者上线 → 大概率就是没有位点的原因；
 *  4. 其余沿用 CODE 17 / 206 / 未知失败的通用解释。
 */
internal enum class ProgressHint {
    /** 组在 broker 上不存在（名字拼错 / 已被删除）。 */
    GROUP_MISSING,

    /** 没有该组的 ACL 权限。 */
    NO_PERMISSION,

    /** 组存在，但没有在线消费者 —— 从未提交过位点。 */
    NO_MEMBERS,

    /** CODE 17：重试队列在 NameServer 无路由，且组内有成员。 */
    RETRY_TOPIC_NO_ROUTE,

    /** CODE 206：位点表为空。 */
    NO_CONSUME_STATS,

    /** 其他失败（超时 / 未知）。 */
    FAILED,

    /** 临时浏览组（CID_mqclient_browse_ 前缀）：不参与真实消费。 */
    TEMP_GROUP,
}

/** 由详情快照推导 [ProgressHint]；纯函数，可单测。 */
internal fun progressHint(detail: RocketMqGroupDetailSnapshot): ProgressHint = when {
    // ① 最根本：组不在 broker 上。此时「没有位点」是必然结果，说别的都是噪音。
    detail.groupMissing -> ProgressHint.GROUP_MISSING

    // ② 权限问题与组状态无关，优先说清楚（否则用户会去查根本没问题的组）。
    detail.progressState == RocketMqProgressState.NO_PERMISSION -> ProgressHint.NO_PERMISSION

    // ③ 组在、但从来没有消费者上线过：这正是「查不到位点」最常见的真实原因。
    detail.progressState == RocketMqProgressState.RETRY_TOPIC_NO_ROUTE &&
        (detail.memberCount ?: 0) == 0 -> ProgressHint.NO_MEMBERS

    detail.progressState == RocketMqProgressState.RETRY_TOPIC_NO_ROUTE -> ProgressHint.RETRY_TOPIC_NO_ROUTE
    detail.progressState == RocketMqProgressState.NO_CONSUME_STATS -> ProgressHint.NO_CONSUME_STATS
    detail.progressState == RocketMqProgressState.TEMP_GROUP -> ProgressHint.TEMP_GROUP
    else -> ProgressHint.FAILED
}
