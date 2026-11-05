package com.mqclient.rabbitmq

import com.mqclient.core.ServiceException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.java.Java
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.io.IOException
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

/**
 * 队列详情（`GET /api/queues/{vhost}/{name}`）：队列属性对话框的实时数据源 ——
 * 慢扫快照不含 arguments 与 queue_type，只有详情端点有；计数与快扫同字段。
 */
@Serializable
data class RabbitMqQueueDetailDto(
    val name: String = "",
    val vhost: String = "/",
    val state: JsonElement? = null,
    /** classic（默认）/ quorum —— 详情端点才有，慢扫 DTO 没这字段。 */
    @SerialName("queue_type") val queueType: String = "classic",
    val durable: Boolean = false,
    @SerialName("auto_delete") val autoDelete: Boolean = false,
    val exclusive: Boolean = false,
    val arguments: Map<String, JsonElement> = emptyMap(),
    val messages: Long = 0,
    @SerialName("messages_ready") val messagesReady: Long = 0,
    @SerialName("messages_unacknowledged") val messagesUnacknowledged: Long = 0,
    val consumers: Int = 0,
    /**
     * 消费者明细：详情端点自带（管理端 `clean_consumer_details/1` 生成），队列属性
     * 对话框「消费者」Tab 的唯一数据源。
     *
     * **必须按可空处理**：老版本或关闭 metrics collector（`?disable_stats` /
     * `management_agent.disable_metrics_collector`）时该字段整个缺失或为 null。
     * 注意管理 API **没有** `/api/queues/{vhost}/{name}/consumers` 这个端点（路由表
     * 里只有 `/consumers` 与 `/consumers/{vhost}`），别再去发那次注定 404 的请求。
     */
    @SerialName("consumer_details") val consumerDetails: List<RabbitMqConsumerDto>? = null,
) {
    /** state 的字符串投影：字符串原样；数组（多节点各报各的状态）取首个元素。 */
    val stateText: String?
        get() = when (val s = state) {
            is JsonPrimitive -> s.content
            is JsonArray -> (s.firstOrNull() as? JsonPrimitive)?.content
            else -> null
        }
}

/**
 * 消费者条目（队列详情响应的 `consumer_details` 数组元素）：队列属性对话框
 * 「消费者」Tab 的实时数据源。管理端点返回字段更多（active / prefetch / 通道
 * 详情几十项），ignoreUnknownKeys 只取投影所需。
 */
@Serializable
data class RabbitMqConsumerDto(
    @SerialName("consumer_tag") val consumerTag: String = "",
    @SerialName("ack_required") val ackRequired: Boolean = true,
    @SerialName("channel_details") val channelDetails: RabbitMqChannelDetailsDto? = null,
)

/** 消费者所在通道的连接信息（对端 IP / 端口即消费者客户端的地址）。 */
@Serializable
data class RabbitMqChannelDetailsDto(
    @SerialName("peer_host") val peerHost: String? = null,
    @SerialName("peer_port") val peerPort: Int? = null,
)

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

/** 绑定条目（`GET /api/bindings/{vhost}` 与 `GET /api/exchanges/{vhost}/{name}/bindings/source`）。 */
@Serializable
data class RabbitMqBindingDto(
    val source: String = "",
    val destination: String = "",
    @SerialName("destination_type") val destinationType: String = "queue",
    @SerialName("routing_key") val routingKey: String = "",
    val arguments: Map<String, JsonElement> = emptyMap(),
    /** 删除绑定的路径参数（`DELETE /api/bindings/.../{properties_key}`）；服务端生成。 */
    @SerialName("properties_key") val propertiesKey: String? = null,
)

/** ``POST /api/exchanges/{vhost}/{name}/publish`` 的响应：routed = 是否路由到至少一个队列。 */
@Serializable
data class RabbitMqPublishResultDto(
    val routed: Boolean = false,
)

/**
 * 集群节点条目（`GET /api/nodes`）：概览页节点区块的投影 —— 只取名称 / 类型 /
 * 运行状态与资源告警水位。管理端点实际返回几十个字段（fsu / sockets / gc 相关等），
 * ignoreUnknownKeys 全部忽略。
 */
@Serializable
data class RabbitMqNodeDto(
    val name: String = "",
    /** disc（磁盘节点）/ ram（内存节点）。 */
    val type: String = "disc",
    val running: Boolean = false,
    /** 节点运行时长（毫秒）。 */
    val uptime: Long = 0,
    @SerialName("mem_used") val memUsed: Long = 0,
    @SerialName("mem_limit") val memLimit: Long = 0,
    /** 内存告警：触发后集群会阻塞发布连接。 */
    @SerialName("mem_alarm") val memAlarm: Boolean = false,
    /** 磁盘剩余空间告警：同上。 */
    @SerialName("disk_free_alarm") val diskFreeAlarm: Boolean = false,
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
class RabbitMqAdminClient internal constructor(
    private val config: RabbitMqConfig,
    private val http: HttpClient,
    /** 详情查询专用：**独立连接池**，慢扫拉几百个队列不会把交互请求排队卡死。 */
    private val detailHttp: HttpClient = http,
) : AutoCloseable {

    /** 生产构造：两个独立 Java 引擎实例 —— 扫描与交互请求互不争抢连接池。 */
    constructor(config: RabbitMqConfig) : this(
        config,
        defaultHttpClient(config),
        defaultHttpClient(config),
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val authHeader: String =
        "Basic " + Base64.getEncoder()
            .encodeToString("${config.username}:${config.password}".toByteArray(Charsets.UTF_8))

    /** 路径段编码（vhost 与对象名共用）："/" → %2F；空格矫正（URLEncoder 是 form 语义，+ 号在路径里非法）。 */
    internal fun pathSegment(raw: String): String =
        java.net.URLEncoder.encode(raw, Charsets.UTF_8).replace("+", "%20")

    /** vhost 段（出现频率高，语义化别名）。 */
    internal fun vhostSegment(): String = pathSegment(config.vhost)

    private fun apiUrl(vararg segments: String): String = buildString {
        append(if (config.useTls) "https" else "http")
        append("://").append(config.host.trim()).append(':').append(config.managementPort)
        append("/api")
        segments.forEach { segment ->
            append('/').append(segment)
        }
    }

    /** 连接类异常 → 统一人类可读文案（GET / PUT / DELETE 共用）。 */
    private fun mapConnectionError(e: Throwable): Exception = when (e) {
        is ConnectException -> ServiceException(
            "rabbitmq_err_mgmt_connect",
            args = listOf(config.host, config.managementPort.toString()),
            fallbackMessage = "Cannot connect to %1\$s:%2\$s: check the address and management port (default 15672)", cause = e,
        )
        is UnknownHostException -> ServiceException(
            "rabbitmq_err_host_resolve",
            args = listOf(config.host),
            fallbackMessage = "Cannot resolve host \"%1\$s\": check the spelling and DNS", cause = e,
        )
        is ConnectTimeoutException -> ServiceException(
            "rabbitmq_err_connect_timeout",
            args = listOf(config.connectionTimeout, config.host, config.managementPort.toString()),
            fallbackMessage = "Connection timed out (%1\$ss): the management API at %2\$s:%3\$s is not responding", cause = e,
        )
        is SocketTimeoutException -> ServiceException(
            "rabbitmq_err_request_timeout",
            args = listOf(config.connectionTimeout),
            fallbackMessage = "Request timed out (%1\$ss): the management API is too slow to respond", cause = e,
        )
        // HttpTimeout 插件的请求级超时。**注意它不是 SocketTimeoutException**（Ktor 3.2.3
        // 里继承 java.io.IOException），不进这一支的话用户看到的会是英文原文
        // `Request timeout has expired [url=..., request_timeout=30000 ms]`。
        is HttpRequestTimeoutException -> ServiceException(
            "rabbitmq_err_request_timeout",
            args = listOf(config.connectionTimeout),
            fallbackMessage = "Request timed out (%1\$ss): the management API is too slow to respond", cause = e,
        )
        else -> IllegalStateException(e.message ?: e::class.simpleName ?: "unknown", e)
    }

    /** 状态码 → 统一错误映射；2xx 原样放行。[body] 是响应体，非 2xx 时提取 reason 带进文案。 */
    private fun checkStatus(status: Int, body: String? = null) {
        val reason = reasonOf(body)
        when (status) {
            in 200..299 -> Unit
            // 401 有两种完全不同的原因，不能合并成一句「认证失败」：
            //  - 凭据不对 → {"error":"not_authorised", ...}？不是 —— 那种是下面的情况；
            //  - **tag 不够** → {"error":"not_authorised","reason":"Not monitor user"}。
            //    密码是对的，去改密码只会白忙。实测：只带 management tag 的账号请求
            //    /api/nodes 就是这条（见 RabbitMqScanPartialLiveTest）。
            401 -> throw if (isTagInsufficient(body)) {
                ServiceException(
                    "rabbitmq_err_tag_insufficient",
                    args = listOf(reason),
                    fallbackMessage = "Insufficient permissions (401): the account lacks the required tag%1\$s",
                )
            } else {
                ServiceException(
                    "rabbitmq_err_auth_failed",
                    args = listOf(reason),
                    fallbackMessage = "Authentication failed (401): check the username / password%1\$s",
                )
            }
            403 -> throw ServiceException(
                "rabbitmq_err_forbidden",
                args = listOf(reason),
                fallbackMessage = "Authorization failed (403): insufficient permissions%1\$s",
            )
            404 -> throw ServiceException(
                "rabbitmq_err_not_found",
                args = listOf(reason),
                fallbackMessage = "Not found (404): check the management port and vhost%1\$s",
            )
            else -> throw ServiceException(
                "rabbitmq_err_http_status",
                args = listOf(status, reason),
                fallbackMessage = "Management API returned HTTP %1\$d%2\$s",
            )
        }
    }

    /**
     * 401 是否由「tag 不够」引起（而非凭据错）。
     *
     * RabbitMQ 对这两种都回 401，只能从响应体区分：tag 不够时带
     * `"error":"not_authorised"` 与 `"reason":"Not monitor user"` 这类理由。
     */
    private fun isTagInsufficient(body: String?): Boolean {
        val text = body.orEmpty()
        if (!text.contains("not_authorised")) return false
        // 凭据真的错时 RabbitMQ 回 "Login was refused using authentication mechanism PLAIN"，
        // 那时没有 not_authorised，也就不会走到这里。
        return true
    }

    companion object {
        /**
         * 队列详情 GET 的硬超时（15s）：属性对话框打开时的交互请求，详见 [queueDetail]。
         * 原为 5s —— 慢扫占用连接池 / broker 繁忙时偶发「数据加载超时」；单对象查询
         * 正常只要毫秒级，15s 仍有三个数量级余量，同时给排队留足时间。
         */
        const val DETAIL_TIMEOUT_MS = 15_000L

        /** RabbitMQ 错误响应（JSON）里 reason 字段的提取；plain text 响应整体兜底。 */
        private val REASON_REGEX = Regex("\"reason\"\\s*:\\s*\"([^\"]+)\"")

        /** 非 2xx 的原因后缀：JSON 取 reason 字段，否则原文；截断 200 字符。 */
        private fun reasonOf(body: String?): String {
            val text = body?.trim().orEmpty()
            if (text.isEmpty()) return ""
            val matched = REASON_REGEX.find(text)?.groupValues?.get(1) ?: text
            val snippet = if (matched.length > 200) matched.take(200) + "…" else matched
            return ": $snippet"
        }
    }

    /** 单次 GET：只负责网络与读 body，异常原样上抛（便于调用方决定重试 / 映射）。 */
    private suspend fun getOnce(
        client: HttpClient,
        segments: Array<out String>,
        timeoutMillis: Long?,
    ): Pair<Int, String> {
        val response: HttpResponse = client.get(apiUrl(*segments)) {
            header(HttpHeaders.Authorization, authHeader)
            if (timeoutMillis != null) timeout { requestTimeoutMillis = timeoutMillis }
        }
        return response.status.value to response.bodyAsText()
    }

    /**
     * GET 请求（JSON 文本）。[timeoutMillis] 非 null 时**只给这一次请求**上硬超时；
     * [useDetailClient] 为 true 时走独立的详情连接池（与后台慢扫 / 快扫隔离），并在
     * 遇到坏 Keep-Alive socket（[IOException]）时自动重试一次 —— JDK HttpClient 会
     * 丢弃坏连接并新建 socket。
     *
     * 详情 GET 与后台扫描不能共用超时 / 连接池：慢扫拉几百个队列时，交互请求会在
     * 队列里排队，等全局 30s（用户还能改更大）与卡死无异。
     */
    private suspend fun getJson(
        vararg segments: String,
        timeoutMillis: Long? = null,
        useDetailClient: Boolean = false,
    ): String {
        val client = if (useDetailClient) detailHttp else http
        // 详情请求对坏 socket 自动重建一次连接；扫描请求保持原语义（不重试）。
        val attempts = if (useDetailClient) 2 else 1
        repeat(attempts) { attempt ->
            // checkStatus 必须留在 try 之外：401 / 403 / 404 抛的是带中文文案的
            // ServiceException，不能被下面的 catch(Exception) 转成 IllegalStateException。
            val response: Pair<Int, String> = try {
                getOnce(client, segments, timeoutMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpRequestTimeoutException) {
                // 这次请求的超时值可能小于全局值（如 15s vs 30s），文案得说真实的那一个。
                // 超时不重试（避免把 15s 翻倍），交给 UI 的重试按钮。
                val seconds = (timeoutMillis ?: (config.connectionTimeout * 1_000L)) / 1_000L
                throw ServiceException(
                    "rabbitmq_err_request_timeout",
                    args = listOf(seconds),
                    fallbackMessage = "Request timed out (%1\$ss): the management API is too slow to respond", cause = e,
                )
            } catch (e: IOException) {
                // 连接被对端关掉 / Keep-Alive 失效：详情请求自动丢弃坏 socket 再试一次。
                if (useDetailClient && attempt == 0) {
                    rabbitMqLog.warn("[Warn] management API connection broken, retrying once on a fresh socket: ${e.message}")
                    return@repeat
                }
                throw mapConnectionError(e)
            } catch (e: Exception) {
                throw mapConnectionError(e)
            }
            checkStatus(response.first, response.second)
            return response.second
        }
        throw mapConnectionError(IOException("management API request failed after retry"))
    }

    /**
     * 写请求（PUT / DELETE / POST）：2xx 视为成功，其余走统一错误映射。返回响应体
     * （publish 端点要读 `{"routed":bool}`；PUT / DELETE 204 时为空串，调用方忽略）。
     */
    private suspend fun mutatingRequest(
        method: HttpMethod,
        body: String?,
        vararg segments: String,
    ): String {
        val response: HttpResponse = try {
            http.request(apiUrl(*segments)) {
                this.method = method
                header(HttpHeaders.Authorization, authHeader)
                body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw mapConnectionError(e)
        }
        val body = response.bodyAsText()
        checkStatus(response.status.value, body)
        return body
    }


    // ---- 五个管理端点（连接验证 / 扫描共用）----

    /** `GET /api/overview`：连接验证用（拿版本 + 集群名记日志）。 */
    suspend fun overview(): RabbitMqOverviewDto =
        json.decodeFromString(RabbitMqOverviewDto.serializer(), getJson("overview"))

    suspend fun queues(): List<RabbitMqQueueDto> =
        json.decodeFromString(ListSerializer(RabbitMqQueueDto.serializer()), getJson("queues", vhostSegment()))

    suspend fun exchanges(): List<RabbitMqExchangeDto> =
        json.decodeFromString(ListSerializer(RabbitMqExchangeDto.serializer()), getJson("exchanges", vhostSegment()))

    suspend fun bindings(): List<RabbitMqBindingDto> =
        json.decodeFromString(ListSerializer(RabbitMqBindingDto.serializer()), getJson("bindings", vhostSegment()))

    /** `GET /api/nodes`：集群节点列表（概览页节点区块，慢扫拉取）。 */
    suspend fun nodes(): List<RabbitMqNodeDto> =
        json.decodeFromString(ListSerializer(RabbitMqNodeDto.serializer()), getJson("nodes"))

    // ---- 写操作与细粒度查询（交换机管理）----

    /**
     * 创建交换机：`PUT /api/exchanges/{vhost}/{name}`。成功 201 / 204。
     * internal 恒 false（内部交换机是 amq.* 系统物件，不提供创建入口）。
     */
    suspend fun createExchange(
        name: String,
        type: String,
        durable: Boolean,
        autoDelete: Boolean,
        arguments: JsonObject = buildJsonObject { },
    ) {
        val body = buildJsonObject {
            put("type", type)
            put("durable", durable)
            put("auto_delete", autoDelete)
            put("internal", false)
            put("arguments", arguments)
        }.toString()
        mutatingRequest(HttpMethod.Put, body, "exchanges", vhostSegment(), pathSegment(name))
    }

    /**
     * 创建队列：`PUT /api/queues/{vhost}/{name}`。成功 201。
     * [arguments] 是 x-arguments 的 JSON 对象（数值 / 布尔 / 字符串混合，由 UI 层
     * 完成字符串到对应类型的转换 —— 与管理端行为一致）。
     */
    suspend fun createQueue(
        name: String,
        durable: Boolean,
        autoDelete: Boolean,
        exclusive: Boolean,
        arguments: JsonObject,
    ) {
        val body = buildJsonObject {
            put("durable", durable)
            put("auto_delete", autoDelete)
            put("exclusive", exclusive)
            put("arguments", arguments)
        }.toString()
        mutatingRequest(HttpMethod.Put, body, "queues", vhostSegment(), pathSegment(name))
    }

    /**
     * 删除交换机：`DELETE /api/exchanges/{vhost}/{name}`。成功 204。
     * 默认交换机（name=""）与 amq.* 由调用方守卫 —— 服务端也会拒绝，但语义更清晰的是
     * 在进入 API 前拦截。
     */
    suspend fun deleteExchange(name: String) {
        mutatingRequest(HttpMethod.Delete, null, "exchanges", vhostSegment(), pathSegment(name))
    }

    /** 指定交换机的绑定（以它为 source）：属性对话框的 Bindings 表实时数据源。 */
    suspend fun exchangeBindings(name: String): List<RabbitMqBindingDto> =
        json.decodeFromString(
            ListSerializer(RabbitMqBindingDto.serializer()),
            getJson("exchanges", vhostSegment(), pathSegment(name), "bindings", "source"),
        )

    /**
     * 添加绑定：`POST /api/bindings/{vhost}/e/{exchange}/{q|e}/{destination}`，body =
     * `{"routing_key","arguments"}`（arguments 第一版固定 `{}`）。成功 201。
     * [destinationType] = "queue"（默认）/ "exchange" —— e→e 绑定走 `e` 路径段。
     */
    suspend fun createBinding(
        exchange: String,
        destination: String,
        destinationType: String,
        routingKey: String,
        arguments: Map<String, String> = emptyMap(),
    ) {
        val body = buildJsonObject {
            put("routing_key", routingKey)
            // 值智能判型（与 RabbitMQ 管理 UI 一致）：整数 → 数字、"true/false" → 布尔、
            // 其余 → 字符串 —— headers 交换机的 x-match 匹配对值类型敏感。
            put("arguments", buildJsonObject {
                arguments.forEach { (k, v) -> put(k, argValue(v)) }
            })
        }.toString()
        mutatingRequest(
            HttpMethod.Post,
            body,
            "bindings",
            vhostSegment(),
            "e",
            pathSegment(exchange),
            if (destinationType == "exchange") "e" else "q",
            pathSegment(destination),
        )
    }

    /**
     * 删除绑定：`DELETE /api/bindings/{vhost}/e/{exchange}/{q|e}/{destination}/{properties_key}`。
     * propertiesKey 来自绑定列表响应（服务端生成的路径转义键，随 routing_key /
     * arguments 变化）；[destinationType] 同 createBinding。
     */
    suspend fun deleteBinding(exchange: String, destination: String, destinationType: String, propertiesKey: String) {
        mutatingRequest(
            HttpMethod.Delete,
            null,
            "bindings",
            vhostSegment(),
            "e",
            pathSegment(exchange),
            if (destinationType == "exchange") "e" else "q",
            pathSegment(destination),
            pathSegment(propertiesKey),
        )
    }

    /**
     * 指定队列详情（实时查询，不走缓存）：属性对话框数据源（arguments / queue_type /
     * 计数 / consumer_details）。
     *
     * 走 [DETAIL_TIMEOUT_MS]（15s）且用独立详情连接池：这是用户点开对话框时的一发
     * 交互请求，三个 Tab 都靠它的结果，既不能陪着全局 30s 一起等，也不能被后台慢扫
     * 在连接池里排队卡死。超时抛带中文文案的超时异常，由 UI 转成错误态 + 重试。
     */
    suspend fun queueDetail(
        name: String,
        /** 请求级超时；默认 [DETAIL_TIMEOUT_MS]（15s），测试可注入短值快速验证。 */
        timeoutMillis: Long = DETAIL_TIMEOUT_MS,
    ): RabbitMqQueueDetailDto =
        json.decodeFromString(
            RabbitMqQueueDetailDto.serializer(),
            getJson(
                "queues", vhostSegment(), pathSegment(name),
                timeoutMillis = timeoutMillis,
                useDetailClient = true,
            ),
        )

    /**
     * 清空队列：`DELETE /api/queues/{vhost}/{name}/contents`（只删 Ready，Unacked 不受影响）。成功 204。
     */
    suspend fun purgeQueue(name: String) {
        mutatingRequest(HttpMethod.Delete, null, "queues", vhostSegment(), pathSegment(name), "contents")
    }

    /**
     * 删除队列：`DELETE /api/queues/{vhost}/{name}`。成功 204。有消费者连接时服务端
     * 返回 400（reason 在响应体，checkStatus 原样带进文案）。
     */
    suspend fun deleteQueue(name: String) {
        mutatingRequest(HttpMethod.Delete, null, "queues", vhostSegment(), pathSegment(name))
    }

    /**
     * 测试发布：`POST /api/exchanges/{vhost}/{name}/publish`，body =
     * `{"properties","routing_key","payload","payload_encoding"}`，headers 进
     * `properties.headers`；payloadEncoding 手输文本用 "string"，文件字节用 "base64"
     * （管理端点原生支持，避免二进制被 UTF-8 解码弄坏）。响应 `{"routed":bool}` —— routed=false 表示消息已提交但
     * 没有命中任何队列（无绑定 / key 不匹配等）。默认交换机（name=""）在管理 API 里
     * 编址为 amq.default。这是开发辅助通道：与 AMQP 客户端发布语义不同（非事务、
     * 无 publisher confirm），仅供属性对话框验证路由。
     */
    suspend fun publish(
        exchange: String,
        routingKey: String,
        payload: String,
        headers: Map<String, String>,
        payloadEncoding: String = "string",
    ): Boolean {
        val target = exchange.ifEmpty { "amq.default" }
        val body = buildJsonObject {
            put("properties", buildJsonObject {
                if (headers.isNotEmpty()) {
                    put("headers", buildJsonObject { headers.forEach { (k, v) -> put(k, v) } })
                }
            })
            put("routing_key", routingKey)
            put("payload", payload)
            put("payload_encoding", payloadEncoding)
        }.toString()
        val response = mutatingRequest(HttpMethod.Post, body, "exchanges", vhostSegment(), pathSegment(target), "publish")
        return json.decodeFromString(RabbitMqPublishResultDto.serializer(), response).routed
    }

    /** 绑定 / 参数值的类型投影：整数 → Long、true/false → Boolean、其余保留字符串。 */
    private fun argValue(raw: String): JsonElement = when {
        raw.toLongOrNull() != null -> JsonPrimitive(raw.toLong())
        raw.equals("true", ignoreCase = true) -> JsonPrimitive(true)
        raw.equals("false", ignoreCase = true) -> JsonPrimitive(false)
        else -> JsonPrimitive(raw)
    }

    override fun close() {
        http.close()
        if (detailHttp !== http) detailHttp.close()
    }
}

/**
 * 生产用的 HTTP 客户端：Java 引擎（CIO 不支持定制 SSLContext）、关掉 expectSuccess
 * （状态码自己转文案）、超时按连接配置。
 *
 * 单独抽成顶层函数（而不是写在类里的属性初始化）是为了给测试留缝：
 * [RabbitMqAdminClient] 的 internal 主构造能直接拿到 MockEngine 客户端。
 */
private fun defaultHttpClient(config: RabbitMqConfig): HttpClient = HttpClient(Java) {
    // 状态码自己检查转文案（expectSuccess=true 会先抛不带上下文的异常）。
    expectSuccess = false
    // 自签 CA：信任库配置了就挂定制 SSLContext（AMQP 会话与管理 API 同源）。
    engine {
        rabbitMqSslContext(config.tlsTruststorePath, config.tlsTruststorePassword)?.let { ctx ->
            config { sslContext(ctx) }
        }
    }
    install(HttpTimeout) {
        requestTimeoutMillis = config.connectionTimeout * 1_000L
        connectTimeoutMillis = config.connectionTimeout * 1_000L
        socketTimeoutMillis = config.connectionTimeout * 1_000L
    }
}
