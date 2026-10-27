package com.mqclient.ai.adapter

import com.mqclient.core.AiConnection
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 全进程共享的 Ktor 客户端（惰性创建）。
 *
 * **为什么在 jvmMain**：两个引擎（`io.ktor.client.engine.java`）与 trust-all 路径
 * 都要 `javax.net.ssl.SSLContext` / `java.security`，这些是 JVM 专用。项目当前只有
 * `jvm()` 一个 target，放在 commonMain 也能编译，但那只是「假装跨平台」—— 一旦加
 * 第二个 target 这里立刻编不过。平台中立的配套函数（[aiJson] / [baseUrlOrThrow] /
 * [throwIfNotSuccess] / [testQuietly]）留在 commonMain 的 AiHttpSupport.kt。
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

    /**
     * 严格校验客户端（默认）。
     *
     * 保留 [Lazy] 对象而不是直接的 `by lazy` 委托：`close()` 需要 `isInitialized`
     * 判断「引擎是否真的建过」，而委托属性本身不暴露这个信息。
     */
    private val strict: Lazy<HttpClient> = lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 120_000
                requestTimeoutMillis = null
            }
        }
    }

    val client: HttpClient get() = strict.value

    /** 按连接取客户端：`ignoreSsl=false`（默认）走严格 CIO；true 走 trust-all。 */
    fun clientFor(connection: AiConnection): HttpClient =
        if (connection.ignoreSsl) trusting.value else strict.value

    /** trust-all 客户端：惰性创建（绝大多数用户不会开启，零启动成本）。 */
    private val trusting: Lazy<HttpClient> = lazy {
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

    /**
     * 关闭两个引擎。由应用退出链（`shutdownApp`）调用。
     *
     * 为什么必须关：Ktor 的 CIO / Java 引擎各自持有 selector 线程与 dispatcher。
     * 桌面应用关窗后进程仍然活着（Nucleus 的窗口生命周期不等于 JVM 退出），这些
     * 线程就一直留着。虽然它们都是 daemon（不会阻止 JVM 退出），但重复开关窗口
     * 的场景里会看见线程数只增不减。
     *
     * **只关真正创建过的**（`isInitialized`）：`by lazy` 的委托在 `getValue` 之前不
     * 会构造引擎；对一个从未用过的字段调 `close()` 会**触发初始化**再关 —— 白白起
     * 一批线程再拆掉，属于「为了清理而制造开销」。
     *
     * 幂等性：Ktor 的 `HttpClient.close()` 可以重复调（内部 engine 关闭是幂等的），
     * 但这里再用 `runCatching` 包一层 —— 关闭失败不应把退出链后续步骤带走。
     */
    fun close() {
        // Lazy.isInitialized() 是函数（接口成员），不是委托属性的那个扩展属性。
        if (strict.isInitialized()) runCatching { strict.value.close() }
        if (trusting.isInitialized()) runCatching { trusting.value.close() }
    }
}
