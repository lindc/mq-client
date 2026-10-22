package com.mqclient.ai.adapter

import com.mqclient.ai.AiException
import com.mqclient.core.AiConnection
import com.mqclient.core.log.Log
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 适配器层的**平台中立**部分：JSON、URL 规范化、错误翻译、连接测试骨架。
 *
 * 与 [AiHttp]（jvmMain，构造 Ktor 客户端）拆开的原因：这几个函数被
 * `OpenAiCompatibleAdapter` / `AnthropicAdapter` 的 commonMain 代码调用 ——
 * 它们只依赖 Ktor 的公共 API 与 kotlinx.serialization，可以留在 commonMain。
 * 客户端构造要 `io.ktor.client.engine.java` / `javax.net.ssl.SSLContext`，
 * 那部分放在 jvmMain 才是诚实的（见 AiHttp 的 KDoc）。
 *
 * 残余依赖说明：[isAiSslFailure] 仍用 `javax.net.ssl` / `java.security.cert` 的
 * 异常类型做分类，而 [testQuietly] 依赖它 —— 两者因此留在 commonMain。项目当前
 * 只有 `jvm()` 一个 target，这个残余依赖是已知且被记录的；真要加第二个 target，
 * 这里应改成 expect/actual。
 */

/**
 * 适配器层统一日志 tag（public：provider 模块的适配器也要用它，`internal` 只在
 * 本模块内可见）。
 */
const val AI_ADAPTER_LOG_TAG = "ai-adapter"

/**
 * 适配器层共享的 JSON 解析器：宽松（服务端多加字段、格式小偏差都不致命）。
 *
 * 供所有适配器模块共用（Anthropic 等第二个适配器模块依赖本模块取用）——
 * 曾经是 internal，多适配器时代放宽为 public（无逻辑改动）。
 */
val aiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 连接的 baseUrl，去掉首尾空白与尾斜杠；没配就抛 [AiException]。
 * 错误信息带连接名 —— 用户一眼知道该改哪条连接。
 */
fun AiConnection.baseUrlOrThrow(): String {
    val url = baseUrl.trim().trimEnd('/')
    if (url.isEmpty()) throw AiException("连接「$name」还没填接口地址（baseUrl）")
    return url
}

/**
 * 非 2xx 统一转 [AiException]。正文里若能解析出服务端的 `error.message` 就带上它
 * （OpenAI 兼容生态的报错形状大体一致），否则截一段原始正文。错误响应不是流，可以放心
 * [bodyAsText]。
 */
suspend fun HttpResponse.throwIfNotSuccess(provider: String) {
    if (status.isSuccess()) return
    val body = runCatching { bodyAsText() }.getOrNull().orEmpty().trim()
    val detail = extractErrorMessage(body) ?: body.take(400)
    throw AiException(
        message = "$provider 请求失败：HTTP ${status.value}" + if (detail.isNotEmpty()) " — $detail" else "",
        statusCode = status.value,
    )
}

/** `{"error":{"message": "..."}}` → `...`；解析不出来返回 null。 */
internal fun extractErrorMessage(body: String): String? =
    runCatching {
        val error = (aiJson.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
            ?: return@runCatching null
        ((error["message"] as? JsonPrimitive)?.contentOrNull)?.take(400)
    }.getOrNull()

/**
 * [testConnection] 的公共骨架：跑最小请求，成功 true；失败记日志返回 false ——
 * **SSL 类失败除外**：重抛带指引前缀的 [AiException]（自签名证书是用户可自行修复
 * 的配置问题，吞掉它测试按钮就只能显示「详情见日志」；非 SSL 失败保持原契约，
 * 服务端 5xx 之类的错误仍走 false）。
 *
 * CancellationException 必须原样抛出（它是取消的正常控制流，吞掉会让协程取消失效）。
 */
suspend fun testQuietly(provider: String, connectionName: String, block: suspend () -> Unit): Boolean =
    try {
        block()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Log.warn(AI_ADAPTER_LOG_TAG, "testConnection failed for '$connectionName' ($provider): $error")
        // SSL 失败重抛（消息带 [SSL_HINT_PREFIX]，UI 层经 AiSslHint 翻译成指引文案）。
        if (error.isAiSslFailure()) throw AiException(SSL_HINT_PREFIX + firstMessage(error), cause = error)
        false
    }

/** SSL 失败重抛消息的前缀：UI 层的 SSL 分类按消息内容匹配，靠它穿透包装。 */
const val SSL_HINT_PREFIX = "SSL handshake failed — "

/** 异常（含 cause 链）是否为 SSL/TLS 证书校验类失败（类型 + 消息双层匹配）。 */
fun Throwable.isAiSslFailure(): Boolean =
    generateSequence(this as Throwable?) { it.cause }
        .take(8)
        .any { e ->
            e is javax.net.ssl.SSLException ||
                e is java.security.cert.CertificateException ||
                e is java.security.cert.CertPathBuilderException ||
                e::class.simpleName?.contains("SSL", ignoreCase = true) == true
        } ||
        generateSequence(this as Throwable?) { it.cause }
            .take(8)
            .mapNotNull { it.message }
            .any { m ->
                listOf("ssl", "handshake", "pkix", "certificate", "trust anchor").any { m.contains(it, ignoreCase = true) }
            }

/** cause 链里第一条非空消息。 */
private fun firstMessage(error: Throwable): String =
    generateSequence(error as Throwable?) { it.cause }.mapNotNull { it.message }.firstOrNull()
        ?: error::class.simpleName
        ?: "unknown"
