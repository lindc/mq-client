package com.mqclient.ibmmq.ui

import javax.net.ssl.SSLContext

/**
 * 「加密套件」下拉的候选列表：应用首次访问时经 JVM 的 [SSLContext] 查询一次
 * 并缓存在内存里（[by lazy] 的单例语义 —— 之后整个进程都读同一份）。
 *
 * 查询规则：取默认 SSLContext 支持的全部套件，只留 TLS 1.2+（`TLS_` 前缀，
 * 顺带排掉 `SSL_` 老协议），剔除不安全家族（RC4 / DES / NULL / EXPORT），
 * 按字母序排好；查询失败（无可用 provider 等）回落到 [FALLBACK] 内置清单。
 *
 * 第一项永远是空字符串 =「自动协商（默认）」—— 不在 TLS 层钉死套件，交给
 * JSSE 与 MQ 队列管理器协商。
 */
object CipherSuiteOptions {

    /** 查询失败时的兜底清单（主流 JVM / MQ 组合都能接受的 TLS 1.2+ 套件）。 */
    private val FALLBACK: List<String> = listOf(
        "TLS_RSA_WITH_AES_128_CBC_SHA256",
        "TLS_RSA_WITH_AES_256_CBC_SHA256",
        "ECDHE_RSA_AES_128_GCM_SHA256",
        "ECDHE_RSA_AES_256_GCM_SHA384",
        "TLS_AES_128_GCM_SHA256",
        "TLS_AES_256_GCM_SHA384",
    )

    /** 按名字片段剔除的不安全家族。 */
    private val EXCLUDED_SUBSTRINGS: List<String> = listOf("RC4", "DES", "NULL", "EXPORT")

    /** 下拉候选：首项空串（自动协商）+ 查询到的可用套件。UI 用 `remember` 持有。 */
    val all: List<String> by lazy { listOf("") + load() }

    private fun load(): List<String> = try {
        SSLContext.getDefault().supportedSSLParameters.cipherSuites
            .filter { suite ->
                suite.startsWith("TLS_") && EXCLUDED_SUBSTRINGS.none { suite.contains(it) }
            }
            .sorted()
            .ifEmpty { FALLBACK }
    } catch (t: Throwable) {
        FALLBACK
    }
}
