package com.mqclient.ai

/**
 * AI 请求的统一异常：携带 HTTP 状态码（网络层失败时为 null）与服务端说明，
 * 消息面向用户可读（面板 / 设置界面直接展示）。
 *
 * 适配器只负责抛；**不**在这里往 `CoreModule.reportError` 报 —— 那是给「用户
 * 应该看得见的意外」用的，一次临时网络抖动的正常失败不该弹全局错误。
 */
class AiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
