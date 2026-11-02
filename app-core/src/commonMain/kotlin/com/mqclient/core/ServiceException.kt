package com.mqclient.core

/**
 * 业务模块（*-service 层）面向用户的错误基类：携带 i18n 错误键 + 参数，展示端
 * （ui-common 的 `Throwable.userMessage()`）在**渲染时点**查当前语言表翻译。
 *
 * 为什么不在 service 层直接翻译：
 *  - i18n 引擎（LanguageResources）在 ui-common 且内嵌 Compose 状态（revision 是
 *    mutableStateOf），依赖方向 ui-common → app-core，service 模块够不着；
 *  - 语言支持热切换，而错误对象**先产生、后展示**（存进 lastError / LoadState，
 *    展示时语言可能已变）—— 抛出时点翻译会渲染出过期语言，解析必须在展示时点。
 *
 * [message] 是**英文兜底文案**（与默认语言一致；日志与缺键回落路径可见）——
 * service 层不再产出中文文案，见各模块 `*_err_*` 翻译键。
 */
open class LocalizedException(
    val errorKey: String,
    val args: List<Any> = emptyList(),
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** 通用业务异常：service 层所有面向用户的错误统一用它抛出。
 *
 * [cause] 走构造参数（不要 `initCause`：`Exception(message, null)` 会把 cause 槽位
 * 冻结成 null，事后 `initCause` 必抛 "Can't overwrite cause"）。
 */
class ServiceException(
    errorKey: String,
    args: List<Any> = emptyList(),
    fallbackMessage: String,
    cause: Throwable? = null,
) : LocalizedException(errorKey, args, fallbackMessage, cause)
