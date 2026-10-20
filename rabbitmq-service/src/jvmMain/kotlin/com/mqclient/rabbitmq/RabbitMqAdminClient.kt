package com.mqclient.rabbitmq

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpTimeout
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
 * 消费者条目（`GET /api/queues/{vhost}/{name}/consumers`）：队列属性对话框
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
        is ConnectException -> IllegalStateException(friendlyConnectError(), e)
        is UnknownHostException -> IllegalStateException("无法解析主机「${config.host}」：检查地址拼写与 DNS", e)
        is ConnectTimeoutException -> IllegalStateException(
            "连接超时（${config.connectionTimeout}s）：管理 API ${config.host}:${config.managementPort} 无响应",
            e,
        )
        is SocketTimeoutException -> IllegalStateException(
            "请求超时（${config.connectionTimeout}s）：管理 API 响应过慢",
            e,
        )
        else -> IllegalStateException(e.message ?: e::class.simpleName ?: "unknown", e)
    }

    /** 状态码 → 统一错误映射；2xx 原样放行。[body] 是响应体，非 2xx 时提取 reason 带进文案。 */
    private fun checkStatus(status: Int, body: String? = null) {
        val reason = reasonOf(body)
        when (status) {
            in 200..299 -> Unit
            401 -> throw IllegalStateException("认证失败（401）：检查用户名 / 密码$reason")
            403 -> throw IllegalStateException("授权失败（403）：账号权限不足$reason")
            404 -> throw IllegalStateException("未找到（404）：检查管理端口与 vhost 是否正确$reason")
            else -> throw IllegalStateException("管理 API 返回 HTTP $status$reason")
        }
    }

    companion object {
        /** RabbitMQ 错误响应（JSON）里 reason 字段的提取；plain text 响应整体兜底。 */
        private val REASON_REGEX = Regex("\"reason\"\\s*:\\s*\"([^\"]+)\"")

        /** 非 2xx 的原因后缀：JSON 取 reason 字段，否则原文；截断 200 字符。 */
        private fun reasonOf(body: String?): String {
            val text = body?.trim().orEmpty()
            if (text.isEmpty()) return ""
            val matched = REASON_REGEX.find(text)?.groupValues?.get(1) ?: text
            val snippet = if (matched.length > 200) matched.take(200) + "…" else matched
            return "：$snippet"
        }
    }

    private suspend fun getJson(vararg segments: String): String {
        val response: HttpResponse = try {
            http.get(apiUrl(*segments)) { header(HttpHeaders.Authorization, authHeader) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw mapConnectionError(e)
        }
        val body = response.bodyAsText()
        checkStatus(response.status.value, body)
        return body
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

    /** 连接类错误的统一文案（host:port 指向管理 API）。 */
    private fun friendlyConnectError(): String =
        "无法连接 ${config.host}:${config.managementPort}：检查地址与管理端口（默认 15672）"

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
    suspend fun createExchange(name: String, type: String, durable: Boolean, autoDelete: Boolean) {
        val body = buildJsonObject {
            put("type", type)
            put("durable", durable)
            put("auto_delete", autoDelete)
            put("internal", false)
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

    /** 指定队列详情（实时查询，不走缓存）：属性对话框数据源（arguments / queue_type / 计数）。 */
    suspend fun queueDetail(name: String): RabbitMqQueueDetailDto =
        json.decodeFromString(
            RabbitMqQueueDetailDto.serializer(),
            getJson("queues", vhostSegment(), pathSegment(name)),
        )

    /**
     * 指定队列的消费者列表（实时查询，不走缓存）：`GET
     * /api/queues/{vhost}/{name}/consumers`。队列不存在时服务端 404 /
     * 无权限 403，由 checkStatus 统一转文案。
     */
    suspend fun queueConsumers(name: String): List<RabbitMqConsumerDto> =
        json.decodeFromString(
            ListSerializer(RabbitMqConsumerDto.serializer()),
            getJson("queues", vhostSegment(), pathSegment(name), "consumers"),
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
    }
}
