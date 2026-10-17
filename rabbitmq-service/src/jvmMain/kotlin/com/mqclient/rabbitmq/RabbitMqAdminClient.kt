package com.mqclient.rabbitmq

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// ---------------------------------------------------------------------------
// 管理 API 响应 DTO（kotlinx.serialization，ignoreUnknownKeys —— 管理端点返回的字段
// 远多于我们关心的，版本间还会增减；只取投影所需）。
// ---------------------------------------------------------------------------

/** `GET /api/overview`：连接验证 + 集群信息。 */
@Serializable
data class RabbitMqOverviewDto(
    @SerialName("rabbitmq_version") val rabbitmqVersion: String = "",
    @SerialName("management_version") val managementVersion: String = "",
    @SerialName("cluster_name") val clusterName: String = "",
    val node: String = "",
)

/**
 * 队列条目（`GET /api/queues/{vhost}`）。慢扫与快扫共用一个 DTO：慢扫关心持久化属性，
 * 快扫只关心计数字段 —— 一次定义，按需取。
 *
 * `state` 特殊：单节点是字符串（running / idle / down），多节点是字符串数组 —— 原样收
 * [JsonElement]，读 [stateText] 收敛为首元素（或原串），别让多节点集群炸掉解析。
 */
@Serializable
data class RabbitMqQueueDto(
    val name: String = "",
    val vhost: String = "/",
    val state: JsonElement? = null,
    val messages: Long = 0,
    @SerialName("messages_ready") val messagesReady: Long = 0,
    @SerialName("messages_unacknowledged") val messagesUnacknowledged: Long = 0,
    val consumers: Int = 0,
    val durable: Boolean = false,
    @SerialName("auto_delete") val autoDelete: Boolean = false,
    val exclusive: Boolean = false,
    val arguments: Map<String, JsonElement> = emptyMap(),
) {
    /** state 的字符串投影：字符串原样；数组（多节点各报各的状态）取首个元素。 */
    val stateText: String?
        get() = when (val s = state) {
            is JsonPrimitive -> s.content
            is JsonArray -> (s.firstOrNull() as? JsonPrimitive)?.content
            else -> null
        }
}

/** 交换机条目（`GET /api/exchanges/{vhost}`；含 name="" 的默认交换机）。 */
@Serializable
data class RabbitMqExchangeDto(
    val name: String = "",
    val vhost: String = "/",
    val type: String = "direct",
    val durable: Boolean = false,
    @SerialName("auto_delete") val autoDelete: Boolean = false,
    val internal: Boolean = false,
)

/** 绑定条目（`GET /api/bindings/{vhost}`；拓扑图的关系来源）。 */
@Serializable
data class RabbitMqBindingDto(
    val source: String = "",
    val destination: String = "",
    @SerialName("destination_type") val destinationType: String = "queue",
    @SerialName("routing_key") val routingKey: String = "",
)

// ---------------------------------------------------------------------------
// 客户端本体
// ---------------------------------------------------------------------------

/**
 * RabbitMQ 管理 API 客户端（HTTP，Basic Auth）：连接验证、对象扫描的唯一 IO 出口。
 *
 * URL 封装：`http(s)://host:managementPort/api/<对象>/{vhost}` —— **vhost 段的编码收敛
 * 在 [vhostSegment] 一处**：vhost 属于路径段，"/"（根 vhost）必须整段编码为 %2F，
 * 用 URLEncoder + 空格矫正（URLEncoder 是 form 语义，空格出 + 号，路径里非法）。
 *
 * 错误约定：HTTP 401 / 403 / 404 / 其他状态码、连接拒绝 / 未知主机 / 超时，一律转成
 * 带原因的 [IllegalStateException]（文案直出 UI，见 [friendlyError]）；CancellationException
 * 原样上抛（协程取消不能吞）。
 */
class RabbitMqAdminClient(private val config: RabbitMqConfig) : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient = HttpClient(CIO) {
        // 状态码自己检查转文案（expectSuccess=true 会先抛不带上下文的异常）。
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = config.connectionTimeout * 1_000L
            connectTimeoutMillis = config.connectionTimeout * 1_000L
            socketTimeoutMillis = config.connectionTimeout * 1_000L
        }
    }

    private val authHeader: String =
        "Basic " + Base64.getEncoder()
            .encodeToString("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))

    /** vhost → 路径段："/" → %2F；空格矫正（URLEncoder 是 form 语义，+ 号在路径里非法）。 */
    internal fun vhostSegment(): String =
        java.net.URLEncoder.encode(config.vhost, Charsets.UTF_8).replace("+", "%20")

    private fun apiUrl(vararg segments: String): String = buildString {
        append(if (config.useTls) "https" else "http")
        append("://").append(config.host.trim()).append(':').append(config.managementPort)
        append("/api")
        segments.forEach { segment ->
            append('/').append(segment)
        }
    }

    private suspend fun getJson(vararg segments: String): String {
        val response: HttpResponse = try {
            http.get(apiUrl(*segments)) { header(HttpHeaders.Authorization, authHeader) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConnectException) {
            throw IllegalStateException(friendlyConnectError(), e)
        } catch (e: UnknownHostException) {
            throw IllegalStateException("无法解析主机「${config.host}」：检查地址拼写与 DNS", e)
        } catch (e: ConnectTimeoutException) {
            throw IllegalStateException(
                "连接超时（${config.connectionTimeout}s）：管理 API ${config.host}:${config.managementPort} 无响应",
                e,
            )
        } catch (e: SocketTimeoutException) {
            throw IllegalStateException(
                "请求超时（${config.connectionTimeout}s）：管理 API 响应过慢",
                e,
            )
        }
        return when (response.status.value) {
            200 -> response.bodyAsText()
            401 -> throw IllegalStateException("认证失败（401）：检查用户名 / 密码")
            403 -> throw IllegalStateException("授权失败（403）：账号权限不足")
            404 -> throw IllegalStateException("未找到（404）：检查管理端口与 vhost 是否正确")
            else -> throw IllegalStateException("管理 API 返回 HTTP ${response.status.value}")
        }
    }

    /** 连接类错误的统一文案（host:port 指向管理 API）。 */
    private fun friendlyConnectError(): String =
        "无法连接 ${config.host}:${config.managementPort}：检查地址与管理端口（默认 15672）"

    // ---- 四个管理端点（连接验证 / 扫描共用）----

    /** `GET /api/overview`：连接验证用（拿版本 + 集群名记日志）。 */
    suspend fun overview(): RabbitMqOverviewDto =
        json.decodeFromString(RabbitMqOverviewDto.serializer(), getJson("overview"))

    suspend fun queues(): List<RabbitMqQueueDto> =
        json.decodeFromString(ListSerializer(RabbitMqQueueDto.serializer()), getJson("queues", vhostSegment()))

    suspend fun exchanges(): List<RabbitMqExchangeDto> =
        json.decodeFromString(ListSerializer(RabbitMqExchangeDto.serializer()), getJson("exchanges", vhostSegment()))

    suspend fun bindings(): List<RabbitMqBindingDto> =
        json.decodeFromString(ListSerializer(RabbitMqBindingDto.serializer()), getJson("bindings", vhostSegment()))

    override fun close() {
        http.close()
    }
}
