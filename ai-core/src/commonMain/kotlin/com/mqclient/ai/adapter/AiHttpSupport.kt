package com.mqclient.ai.adapter

import com.mqclient.ai.AiException
import com.mqclient.core.AiConnection
import com.mqclient.core.log.Log
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * 非流式请求的整体超时（毫秒）。
 *
 * 为什么需要单独一个：`AiHttp` 为了 SSE 流式回复把 `requestTimeoutMillis` 设成了 `null`
 * （流式可能持续数分钟，整体超时会中途砍断），但**非流式**的 `chat()` 复用了同一个
 * 客户端。服务端只要「细水长流」地吐字节或 keep-alive 帧，`socketTimeoutMillis`（120s）
 * 就永远不触发 —— AI 面板会一直停在「正在回复」，用户只能点「停止」。
 */
const val NON_STREAMING_TIMEOUT_MS: Long = 60_000L

/**
 * 给非流式请求套一个**整体**超时，超时转成带连接名的 [AiException]。
 *
 * 分两层：[withNonStreamingDeadline] 管整体截止时间；`AiHttp` 的
 * `socketTimeoutMillis` 继续管「连接活着但长时间没数据」。两者互补，缺一不可。
 *
 * @param timeoutMs 默认取 [NON_STREAMING_TIMEOUT_MS]；适配器把它做成构造参数，
 *   测试传小值以验证超时路径（否则得等一分钟）。
 */
suspend fun <T : Any> withNonStreamingDeadline(
    connectionName: String,
    timeoutMs: Long = NON_STREAMING_TIMEOUT_MS,
    block: suspend () -> T,
): T = coroutineScope {
    // 为什么不用 withTimeout + catch(TimeoutCancellationException)：超时与外部取消都是
    // CancellationException，而 Ktor 内部会把取消换成它自己 job 的异常 —— 靠**异常类型**
    // 归因不可靠（实测：同一个场景时而报超时、时而报「被取消」）。
    //
    // 改成显式竞争：定时器先 complete 截止信号**再**取消任务，于是「取消来自谁」由一个
    // 自己写入的信号回答，不靠猜异常归属。
    // 用 CompletableDeferred 而不是普通 Boolean：它跨线程完成/读取有明确内存语义
    // （普通 var 的可见性只能靠 cancel/await 的原子状态转换侧面保证，是形式上的数据竞争）。
    val deadlineHit = CompletableDeferred<Unit>()
    val work = async { block() }
    val timer = launch {
        delay(timeoutMs)
        deadlineHit.complete(Unit) // 先置位再取消：await 那边一定能看到
        work.cancel()
    }
    try {
        return@coroutineScope work.await()
    } catch (cancellation: CancellationException) {
        if (deadlineHit.isCompleted) {
            throw AiException(
                "请求超时（${timeoutMs / 1000}s）：连接「$connectionName」长时间没有完整响应",
                cause = cancellation,
            )
        }
        // 真正的取消（用户点停止 / 退出 / 上游取消）：原样上抛。
        throw cancellation
    } finally {
        timer.cancel()
    }
}

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
