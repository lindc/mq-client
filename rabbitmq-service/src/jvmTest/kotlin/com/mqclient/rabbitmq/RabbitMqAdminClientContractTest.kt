package com.mqclient.rabbitmq

import com.mqclient.core.ServiceException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RabbitMQ 管理 API **写操作**的契约测试（MockEngine，不碰真实集群）。
 *
 * 为什么只测写操作：读端点错了顶多显示空列表，写端点错了会改别人的集群 ——
 * createExchange / createQueue / delete* / purge / binding / publish 的
 * HTTP 方法、路径段（尤其 vhost 的 `%2F` 编码与 binding 的 `e`/`q` 段）以及
 * 请求体字段形状，都必须是管理 API 认得的那个样子。
 *
 * 同时守住错误分类（401 / 403 / 404 / 5xx → 带原因的中文文案）和
 * CancellationException 原样上抛（协程取消不能被包装成业务异常）。
 *
 * 读端点里只挑 **queueDetail**（包括其 `consumer_details` 字段）做解析契约：它曾经
 * 因为指向一个不存在的端点（`/api/queues/{vhost}/{name}/consumers`，管理 API 只有
 * `/consumers`）而让「消费者」Tab 永远转圈，这里把响应形状钉在测试里。
 */
class RabbitMqAdminClientContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** 记录下来的请求快照：方法 + 路径 + 查询参数 + 请求体。 */
    private data class Recorded(
        val method: String,
        val path: String,
        val body: String,
        val authorization: String?,
    )

    /**
     * 建客户端并捕获实际发出的请求。
     *
     * [respond] 决定响应（状态码 + 正文），默认 204 无正文。
     */
    private fun client(
        vhost: String = "/",
        respond: (Recorded) -> Pair<HttpStatusCode, String> = { HttpStatusCode.NoContent to "" },
    ): Pair<RabbitMqAdminClient, MutableList<Recorded>> {
        val seen = mutableListOf<Recorded>()
        val engine = MockEngine { request ->
            val recorded = Recorded(
                method = request.method.value,
                path = request.url.encodedPath,
                body = request.body.toByteArray().decodeToString(),
                authorization = request.headers[HttpHeaders.Authorization],
            )
            seen += recorded
            val (status, body) = respond(recorded)
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val config = RabbitMqConfig(
            host = "rabbit.example.com",
            managementPort = 15672,
            vhost = vhost,
            username = "mqclient",
            password = "secret",
        )
        return RabbitMqAdminClient(config, HttpClient(engine)) to seen
    }

    private fun io.ktor.http.content.OutgoingContent.toByteArray(): ByteArray =
        (this as? io.ktor.http.content.TextContent)?.text?.encodeToByteArray() ?: ByteArray(0)

    @Test
    fun `createExchange - PUT 到 exchanges 且 body 字段名与内部交换机标记正确`() = runBlocking {
        val (admin, seen) = client(vhost = "/")
        admin.createExchange(
            name = "orders",
            type = "topic",
            durable = true,
            autoDelete = false,
            arguments = buildJsonObject { put("alternate-exchange", "ae") },
        )

        val req = seen.single()
        assertEquals("PUT", req.method)
        // 根 vhost 必须整段编码为 %2F（用 "/" 会被当成路径分隔）。
        assertEquals("/api/exchanges/%2F/orders", req.path)
        val body = json.parseToJsonElement(req.body).jsonObject
        assertEquals("topic", body["type"]?.jsonPrimitive?.content)
        assertEquals(true, body["durable"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, body["auto_delete"]?.jsonPrimitive?.content?.toBoolean())
        // 内部交换机是 amq.* 系统物件，恒 false —— 不能由调用方打开。
        assertEquals(false, body["internal"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("ae", body["arguments"]?.jsonObject?.get("alternate-exchange")?.jsonPrimitive?.content)
        assertTrue(req.authorization?.startsWith("Basic ") == true)
    }

    @Test
    fun `createQueue - PUT 到 queues 且携带 durable auto_delete exclusive arguments`() = runBlocking {
        val (admin, seen) = client()
        admin.createQueue(
            name = "orders.q",
            durable = true,
            autoDelete = false,
            exclusive = false,
            arguments = buildJsonObject { put("x-max-length", 1000) },
        )

        val req = seen.single()
        assertEquals("PUT", req.method)
        assertEquals("/api/queues/%2F/orders.q", req.path)
        val body = json.parseToJsonElement(req.body).jsonObject
        assertEquals(true, body["durable"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, body["auto_delete"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(false, body["exclusive"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("1000", body["arguments"]?.jsonObject?.get("x-max-length")?.jsonPrimitive?.content)
    }

    @Test
    fun `vhost 编码 - 非根 vhost 与空格都按路径段转义`() = runBlocking {
        val (admin, seen) = client(vhost = "my vhost/1")
        admin.deleteQueue("q")

        // 空格 → %20（不是 URLEncoder 的 +），斜杠 → %2F。
        assertEquals("/api/queues/my%20vhost%2F1/q", seen.single().path)
    }

    @Test
    fun `deleteExchange 与 deleteQueue 与 purgeQueue - 方法与路径各不相同`() = runBlocking {
        val (admin, seen) = client()
        admin.deleteExchange("ex")
        admin.deleteQueue("q")
        admin.purgeQueue("q")

        assertEquals(
            listOf(
                "/api/exchanges/%2F/ex",
                "/api/queues/%2F/q",
                // purge 只删 Ready，路径带 contents 尾段 —— 与 deleteQueue 差这一个段。
                "/api/queues/%2F/q/contents",
            ),
            seen.map { it.path },
        )
        assertTrue(seen.all { it.method == "DELETE" })
    }

    @Test
    fun `createBinding - POST 到 bindings 且 destinationType 决定 e 或 q 段`() = runBlocking {
        val (admin, seen) = client()
        admin.createBinding("ex", "q1", destinationType = "queue", routingKey = "a.b")
        admin.createBinding("ex", "ex2", destinationType = "exchange", routingKey = "c")

        assertEquals(
            listOf(
                "/api/bindings/%2F/e/ex/q/q1",
                "/api/bindings/%2F/e/ex/e/ex2",
            ),
            seen.map { it.path },
        )
        assertTrue(seen.all { it.method == "POST" })
        val first = json.parseToJsonElement(seen[0].body).jsonObject
        assertEquals("a.b", first["routing_key"]?.jsonPrimitive?.content)
    }

    @Test
    fun `createBinding - arguments 值按类型推断 整数 布尔 字符串`() = runBlocking {
        val (admin, seen) = client()
        admin.createBinding(
            exchange = "ex",
            destination = "q1",
            destinationType = "queue",
            routingKey = "k",
            arguments = mapOf("count" to "42", "flag" to "true", "text" to "abc"),
        )

        val args = json.parseToJsonElement(seen.single().body)
            .jsonObject["arguments"]!!.jsonObject
        // headers 交换机的 x-match 对值类型敏感：42 必须是数字而不是 "42"。
        assertEquals("42", args["count"]!!.jsonPrimitive.content)
        assertEquals(true, args["flag"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("abc", args["text"]!!.jsonPrimitive.content)
        // 原始类型确实是数字 / 布尔（不是被引号包起来的字符串）。
        assertTrue(args["count"]!!.jsonPrimitive.isString.not())
        assertTrue(args["flag"]!!.jsonPrimitive.isString.not())
        assertTrue(args["text"]!!.jsonPrimitive.isString)
    }

    @Test
    fun `deleteBinding - propertiesKey 作为最后一个路径段被转义`() = runBlocking {
        val (admin, seen) = client()
        admin.deleteBinding("ex", "q1", destinationType = "queue", propertiesKey = "key with space")

        val req = seen.single()
        assertEquals("DELETE", req.method)
        assertEquals("/api/bindings/%2F/e/ex/q/q1/key%20with%20space", req.path)
    }

    @Test
    fun `publish - 默认交换机映射为 amq_default 且返回 routed`() = runBlocking {
        val (admin, seen) = client { HttpStatusCode.OK to """{"routed":true}""" }
        val routed = admin.publish(
            exchange = "",
            routingKey = "rk",
            payload = "hello",
            headers = mapOf("h1" to "v1"),
        )

        assertTrue(routed)
        val req = seen.single()
        assertEquals("POST", req.method)
        assertEquals("/api/exchanges/%2F/amq.default/publish", req.path)
        val body = json.parseToJsonElement(req.body).jsonObject
        assertEquals("rk", body["routing_key"]?.jsonPrimitive?.content)
        assertEquals("hello", body["payload"]?.jsonPrimitive?.content)
        assertEquals("string", body["payload_encoding"]?.jsonPrimitive?.content)
        assertEquals(
            "v1",
            body["properties"]?.jsonObject?.get("headers")?.jsonObject?.get("h1")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `publish - 二进制用 base64 编码且 routed=false 表示未命中队列`() = runBlocking {
        val (admin, seen) = client { HttpStatusCode.OK to """{"routed":false}""" }
        val routed = admin.publish(
            exchange = "ex",
            routingKey = "",
            payload = "AAEC",
            headers = emptyMap(),
            payloadEncoding = "base64",
        )

        assertEquals(false, routed)
        val body = json.parseToJsonElement(seen.single().body).jsonObject
        assertEquals("base64", body["payload_encoding"]?.jsonPrimitive?.content)
        // 无 headers 时不写 properties.headers（空对象会让服务端误判）。
        assertEquals(null, body["properties"]?.jsonObject?.get("headers"))
    }

    @Test
    fun `queueDetail - 解析 consumer_details 明细与计数`() = runBlocking {
        val (admin, seen) = client {
            HttpStatusCode.OK to """
                {
                  "name": "orders.q",
                  "vhost": "/",
                  "state": "running",
                  "queue_type": "quorum",
                  "durable": true,
                  "auto_delete": false,
                  "exclusive": false,
                  "arguments": {"x-queue-type": "quorum", "x-max-length": 1000},
                  "messages": 12,
                  "messages_ready": 10,
                  "messages_unacknowledged": 2,
                  "consumers": 1,
                  "consumer_details": [
                    {
                      "consumer_tag": "amq.ctag-abc",
                      "ack_required": true,
                      "channel_details": {"peer_host": "10.0.0.7", "peer_port": 51324}
                    }
                  ]
                }
            """
        }

        val detail = admin.queueDetail("orders.q")

        // 详情端点本身（不是 /consumers —— 那个路径在管理 API 里不存在）。
        assertEquals("/api/queues/%2F/orders.q", seen.single().path)
        assertEquals("quorum", detail.queueType)
        assertEquals(1, detail.consumers)
        val consumer = detail.consumerDetails?.single()
        assertEquals("amq.ctag-abc", consumer?.consumerTag)
        assertEquals("10.0.0.7", consumer?.channelDetails?.peerHost)
        assertEquals(51324, consumer?.channelDetails?.peerPort)
        assertTrue(consumer?.ackRequired == true)
    }

    @Test
    fun `queueDetail - consumer_details 缺失或 null 都解析成 null 而不是抛错`() = runBlocking {
        // 老版本 / 关闭 metrics collector：字段可能整个缺席，也可能是显式 null。
        for (body in listOf(
            """{"name":"q","consumers":0}""",
            """{"name":"q","consumers":0,"consumer_details":null}""",
        )) {
            val (admin, _) = client { HttpStatusCode.OK to body }
            val detail = admin.queueDetail("q")
            assertEquals(null, detail.consumerDetails, "body=$body 应解析成 null（UI 据此区分「无明细」与「无消费者」）")
        }
    }

    @Test
    fun `queueDetail - 超过 5s 硬超时抛中文文案而不是英文原文`() = runBlocking {
        // 真实的 5s 等待：这例的价值就在「真的会超时触发」——MockEngine 拖 6s，
        // 断言 queueDetail 在 5s（DETAIL_TIMEOUT_MS）时抛错且文案已被翻译。
        val engine = MockEngine {
            kotlinx.coroutines.delay(6_000)
            respond("{\"name\":\"q\"}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        // HttpTimeout 插件必须装（timeout {} 靠它生效；不装就是静默忽略）。
        val admin = RabbitMqAdminClient(
            RabbitMqConfig(host = "h", username = "u", password = "p"),
            HttpClient(engine) { install(io.ktor.client.plugins.HttpTimeout) },
        )

        val error = assertFailsWith<ServiceException> { admin.queueDetail("q") }
        assertEquals("rabbitmq_err_request_timeout", error.errorKey)
        assertTrue(
            error.message?.contains("Request timed out") == true,
            "超时应转成可读兜底文案，实际 '${error.message}'",
        )
        // 回归守卫：HttpRequestTimeoutException 不是 SocketTimeoutException，
        // 落到 else 分支的话用户看到的就是这句英文。
        assertTrue(
            error.message?.contains("Request timeout has expired") != true,
            "英文原文泄露到 UI，实际 '${error.message}'",
        )
    }

    @Test
    fun `错误映射 - 401 403 404 5xx 都转成带键的可读文案`() = runBlocking {
        val cases = listOf(
            HttpStatusCode.Unauthorized to "rabbitmq_err_auth_failed",
            HttpStatusCode.Forbidden to "rabbitmq_err_forbidden",
            HttpStatusCode.NotFound to "rabbitmq_err_not_found",
            HttpStatusCode.InternalServerError to "rabbitmq_err_http_status",
        )
        for ((status, expectedKey) in cases) {
            val (admin, _) = client { status to """{"reason":"boom"}""" }
            val error = assertFailsWith<ServiceException> { admin.deleteQueue("q") }
            assertEquals(expectedKey, error.errorKey, "status=$status 键不符，实际 '${error.message}'")
            // 服务端的 reason 要经 args 透传 —— 用户才知道到底哪里不行。
            assertTrue(
                error.args.any { it.toString().contains("boom") },
                "status=$status 丢了 reason：${error.args}",
            )
        }
    }

    @Test
    fun `错误映射 - 401 区分凭据错与 tag 不足`() = runBlocking {
        // 凭据真错：没有 not_authorised，不该叫用户去查 tag。
        val (badCreds, _) = client { HttpStatusCode.Unauthorized to """{"reason":"Login was refused"}""" }
        val credsError = assertFailsWith<ServiceException> { badCreds.deleteQueue("q") }
        assertEquals(
            "rabbitmq_err_auth_failed", credsError.errorKey,
            "凭据错应提示认证失败，实际 '${credsError.errorKey}'",
        )

        // tag 不够（实测原文）：密码是对的，提示必须指向 tag，否则用户会去改密码。
        val (noTag, _) = client {
            HttpStatusCode.Unauthorized to """{"error":"not_authorised","reason":"Not monitor user"}"""
        }
        val tagError = assertFailsWith<ServiceException> { noTag.nodes() }
        assertEquals(
            "rabbitmq_err_tag_insufficient", tagError.errorKey,
            "tag 不足应提示权限不足，实际 '${tagError.errorKey}'",
        )
        // 服务端的 reason 要经 args 透传（展示端插值进文案，用户才知道缺哪个 tag）。
        assertTrue(
            tagError.args.any { it.toString().contains("Not monitor user") },
            "tag 不足丢了 reason：${tagError.args}",
        )
    }

    @Test
    fun `错误映射 - 2xx 不抛 但 201 与 204 都算成功`() = runBlocking {
        val (created, _) = client { HttpStatusCode.Created to "" }
        created.createQueue("q", durable = true, autoDelete = false, exclusive = false, arguments = buildJsonObject { })

        val (noContent, _) = client { HttpStatusCode.NoContent to "" }
        noContent.purgeQueue("q")
    }

    @Test
    fun `取消原样上抛 - 不被包装成业务异常`() = runBlocking<Unit> {
        val engine = MockEngine { throw kotlinx.coroutines.CancellationException("stop") }
        val admin = RabbitMqAdminClient(
            RabbitMqConfig(host = "h", username = "u", password = "p"),
            HttpClient(engine),
        )
        assertFailsWith<kotlinx.coroutines.CancellationException> { admin.deleteQueue("q") }
    }
}
