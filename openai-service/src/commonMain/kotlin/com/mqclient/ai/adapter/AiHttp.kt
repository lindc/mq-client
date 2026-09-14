package com.mqclient.ai.adapter

import com.mqclient.ai.AiException
import com.mqclient.core.AiConnection
import com.mqclient.core.log.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 适配器层统一日志 tag。 */
internal const val AI_ADAPTER_LOG_TAG = "ai-adapter"

/** 适配器层共享的 JSON 解析器：宽松（服务端多加字段、格式小偏差都不致命）。 */
internal val aiJson: Json = Json {
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
 */
internal object AiHttp {
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
}

/**
 * 连接的 baseUrl，去掉首尾空白与尾斜杠；没配就抛 [AiException]。
 * 错误信息带连接名 —— 用户一眼知道该改哪条连接。
 */
internal fun AiConnection.baseUrlOrThrow(): String {
    val url = baseUrl.trim().trimEnd('/')
    if (url.isEmpty()) throw AiException("连接「$name」还没填接口地址（baseUrl）")
    return url
}

/**
 * 非 2xx 统一转 [AiException]。正文里若能解析出服务端的 `error.message` 就带上它
 * （OpenAI 兼容生态的报错形状大体一致），否则截一段原始正文。错误响应不是流，可以放心
 * [bodyAsText]。
 */
internal suspend fun HttpResponse.throwIfNotSuccess(provider: String) {
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
 * [testConnection] 的公共骨架：跑最小请求，成功 true；失败记日志返回 false。
 *
 * CancellationException 必须原样抛出（它是取消的正常控制流，吞掉会让协程取消失效）；
 * 其余异常全部吃掉 —— 测试按钮的语义是「返回结果」，不是「抛异常」。
 */
internal suspend fun testQuietly(provider: String, connectionName: String, block: suspend () -> Unit): Boolean =
    try {
        block()
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Log.warn(AI_ADAPTER_LOG_TAG, "testConnection failed for '$connectionName' ($provider): $error")
        false
    }
