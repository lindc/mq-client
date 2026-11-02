package com.mqclient.uicommon.i18n

import com.mqclient.core.LocalizedException

/**
 * 异常的用户可读文案：[LocalizedException] 携带 errorKey 的在**展示时点**查当前
 * 语言表翻译（支持热切换 —— 错误先产生后展示，抛出时点翻译会渲染过期语言），
 * 缺键回落英文兜底 `message`；其余异常原样返回 `message`。
 *
 * UI 展示错误的地方（catch 后的 snackbar / Error 态 / 表单错误条）一律用本函数
 * 取代直接读 `e.message` —— 这是 service 层 ErrorKey 异常（app-core 的
 * ServiceException）被正确本地化的唯一通路。
 */
fun Throwable.userMessage(): String = when (this) {
    is LocalizedException -> {
        val resolved = stringResource(errorKey)
        if (resolved != errorKey) resolved.withArg(*args.toTypedArray())
        // 缺键：英文兜底模板也做一次插值，避免裸 %1$s 出现在界面上。
        else message?.withArg(*args.toTypedArray()) ?: errorKey
    }
    else -> message ?: this::class.simpleName ?: "unknown"
}
