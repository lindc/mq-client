package com.mqclient.ibmmq.ui

import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * MQRC 原因码 → 用户可读文案（渲染时点翻译）：已知码查 `ibm_mq_err_mqrc_<码值>`
 * 键（语言表随应用语言切换）；未知码回落 service 层给的英文兜底 [errorMessage]。
 *
 * service 层的结果载体（SendResult / MessageOperationResult / MqscResult）带着
 * reasonCode 与英文兜底 message 走 String 通道，解析统一收口在这里。
 */
fun mqrcUserMessage(reasonCode: Int?, errorMessage: String?): String {
    if (reasonCode != null) {
        val key = "ibm_mq_err_mqrc_$reasonCode"
        val resolved = stringResource(key)
        if (resolved != key) return resolved
    }
    return errorMessage.orEmpty()
}

/** 带键结果（service 抛的 ServiceException 经载体透传）的解析；缺键回落 [fallback]。 */
fun ibmMqErrorText(
    errorKey: String?,
    errorArgs: List<Any>,
    reasonCode: Int?,
    fallback: String?,
): String {
    if (errorKey != null) {
        val resolved = stringResource(errorKey)
        if (resolved != errorKey) return resolved.withArg(*errorArgs.toTypedArray())
    }
    if (reasonCode != null) return mqrcUserMessage(reasonCode, fallback)
    return fallback.orEmpty()
}
