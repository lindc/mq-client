package com.mqclient.ai.adapter

import com.mqclient.ai.AiException
import com.mqclient.core.AiConnection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * trust-all 客户端的**隔离边界**：每个目标 host 一份，绝不共享。
 *
 * 这是安全约束而不是性能优化 —— 共用一个 trust-all 客户端，等于让“不校验证书”
 * 这个性质扩散到所有使用方；按 host 分桶后，影响面被限制在用户显式打开开关的
 * 那一个目标上。
 */
class AiHttpTest {

    @AfterTest
    fun tearDown() {
        // 会真的建 Java 引擎（起线程），用完必须关。
        AiHttp.close()
    }

    private fun connection(baseUrl: String, ignoreSsl: Boolean = true) = AiConnection(
        id = "c1",
        name = "测试连接",
        providerId = "openai-compatible",
        baseUrl = baseUrl,
        ignoreSsl = ignoreSsl,
    )

    @Test
    fun `same host shares one trust-all client`() {
        val a = AiHttp.clientFor(connection("https://self-hosted.local/v1"))
        val b = AiHttp.clientFor(connection("https://self-hosted.local/api"))

        assertSame(a, b, "同一 host 不必反复建引擎")
    }

    @Test
    fun `different hosts never share a trust-all client`() {
        val a = AiHttp.clientFor(connection("https://alpha.local/v1"))
        val b = AiHttp.clientFor(connection("https://beta.local/v1"))

        assertNotSame(a, b, "不同 host 必须各自独立（信任边界隔离）")
    }

    @Test
    fun `different ports on the same host are isolated`() {
        val a = AiHttp.clientFor(connection("https://alpha.local:8443/v1"))
        val b = AiHttp.clientFor(connection("https://alpha.local:9443/v1"))

        assertNotSame(a, b, "端口不同即目标不同")
    }

    @Test
    fun `strict connections share the one strict client`() {
        val a = AiHttp.clientFor(connection("https://alpha.local/v1", ignoreSsl = false))
        val b = AiHttp.clientFor(connection("https://beta.local/v1", ignoreSsl = false))

        assertSame(a, b, "严格客户端本就无所谓目标，共用一份")
        assertSame(a, AiHttp.client)
    }

    @Test
    fun `unparseable base url fails loudly instead of widening trust`() {
        val error = assertFailsWith<AiException> {
            AiHttp.clientFor(connection("not a url"))
        }
        assertEquals(true, error.message!!.contains("无法解析"), error.message)

        assertFailsWith<AiException> { AiHttp.clientFor(connection("   ")) }
    }

    @Test
    fun `host key includes non default port only`() {
        assertEquals("alpha.local", AiHttp.trustAllHostKey("https://alpha.local/v1", "c"))
        assertEquals("alpha.local:8443", AiHttp.trustAllHostKey("https://alpha.local:8443/v1", "c"))
        assertEquals("10.0.0.5:8080", AiHttp.trustAllHostKey("http://10.0.0.5:8080", "c"))
    }

    @Test
    fun `close is idempotent and clears buckets`() {
        AiHttp.clientFor(connection("https://alpha.local/v1"))
        AiHttp.close()
        AiHttp.close()
    }
}
