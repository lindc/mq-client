package com.mqclient.ai.adapter

import com.mqclient.ai.AiException
import com.mqclient.core.AiConnection
import com.mqclient.core.log.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 适配器层统一日志 tag。 */
internal const val AI_ADAPTER_LOG_TAG = "ai-adapter"

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
 * 全进程共享的 Ktor 客户端（惰性创建）。
 *
 * 故意不开 `expectSuccess`：非 2xx 由 [throwIfNotSuccess] 统一转成 [AiException]，
 * 这样错误信息里能带上服务端返回的正文。
 *
 * 超时策略：`requestTimeoutMillis = null` —— SSE 流式回复可能持续数分钟，整体超时
 * 会中途砍断它；改用 `socketTimeoutMillis` 表达「长时间没有新数据才算断」。
 *
 * **SSL**：默认客户端（CIO）走严格校验。`ignoreSsl=true` 的连接经 [clientFor] 拿
 * trust-all 客户端（**Java 引擎**：JDK HttpClient 默认不做主机名校验，配合信任所有
 * 证书的 SSLContext 才能完整绕过链校验 + 主机名校验；CIO 的主机名校验在公开 API
 * 里关不掉 —— 自签名证书 SAN 不含访问主机名/IP 时仅 trustManager 仍会握手失败，
 * 见 Ktor 3.2.3 TLSClientHandshake 的字节码）。两条客户端只差 TLS，超时与错误
 * 口径完全一致。
 *
 * public 化理由同 [aiJson]：所有适配器模块共用同一个客户端（超时策略、日志口径一致）。
 */
object AiHttp {
    val client: HttpClient by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 120_000
                requestTimeoutMillis = null
            }
        }
    }

    /** 按连接取客户端：`ignoreSsl=false`（默认）走严格 CIO；true 走 trust-all。 */
    fun clientFor(connection: AiConnection): HttpClient =
        if (connection.ignoreSsl) trustingClient else client

    /** trust-all 客户端：惰性创建（绝大多数用户不会开启，零启动成本）。 */
    private val trustingClient: HttpClient by lazy {
        // 信任所有证书的 TrustManager：check* 全部空实现 —— 链校验由它接管。
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        HttpClient(Java) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 120_000
                requestTimeoutMillis = null
            }
            engine {
                // JavaHttpConfig.config 暴露原生 JDK HttpClient.Builder（receiver）；
                // JDK HttpClient 默认不做主机名校验（除非显式设 endpointIdentification
                // Algorithm），SSLContext 换成 trust-all 即完整绕过。
                config {
                    sslContext(sslContext)
                }
            }
        }
    }
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
