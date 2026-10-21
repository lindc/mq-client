package com.mqclient.aiui

import com.mqclient.ai.adapter.isAiSslFailure

/**
 * SSL/TLS 异常 → 用户可读文案的映射（「询问 AI」链路与设置页测试按钮的错误出口
 * 统一走这里）：严格校验的客户端遇到自签名证书会抛 SSL 握手类异常，原文（`PKIX
 * path building failed` / `unable to find valid certification path` 等）对用户
 * 毫无行动价值 —— 翻译成「开启忽略 SSL 证书校验」的指引。
 *
 * SSL 类失败的判定在适配器层（[isAiSslFailure]，类型 + cause 链消息双层匹配，
 * `testQuietly` 重抛的 SSL 指引消息同样命中）；本组件只负责 i18n 文案。
 */
object AiSslHint {

    /**
     * 「询问 AI」错误的展示文案：SSL 失败翻译成带指引的提示（i18n 键
     * `ai_ssl_handshake_hint`，缺键回落中文原文）；其余异常原样返回。
     */
    fun displayMessage(error: Throwable): String {
        if (!error.isAiSslFailure()) return error.message ?: error::class.simpleName ?: "unknown"
        val resolved = com.mqclient.uicommon.i18n.stringResource("ai_ssl_handshake_hint")
        return if (resolved != "ai_ssl_handshake_hint") resolved else FALLBACK_SSL_HINT
    }

    private const val FALLBACK_SSL_HINT =
        "SSL 握手失败：服务器证书校验未通过。如果您的服务器使用自签名证书，" +
            "请在 AI 连接设置中开启【忽略 SSL 证书校验】。"
}
