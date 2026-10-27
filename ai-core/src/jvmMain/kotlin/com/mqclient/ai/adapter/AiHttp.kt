package com.mqclient.ai.adapter

import com.mqclient.ai.AiException
import com.mqclient.core.AiConnection
import com.mqclient.core.log.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.java.Java
import io.ktor.client.plugins.HttpTimeout
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Ktor 客户端工厂（惰性创建）。
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
 * ## SSL：信任边界按 host 隔离
 *
 * 默认客户端（CIO）走**严格校验**，全进程共用一个。
 *
 * `ignoreSsl=true` 的连接不共用同一个 trust-all 客户端，而是**按目标 host 派生**
 * （[trustAllClients]，键 = `host[:port]`）。理由：
 *  - 一个 trust-all 客户端如果被多个连接共用，它的「不校验证书」性质就扩散到了
 *    所有使用方；将来任何一处误用（复制连接、复用 clientFor 的返回值）都会把
 *    别的目标的流量也带进「谁签的都信」的通道；
 *  - 按 host 建键后，每个 trust-all 客户端只可能与自己那个 host 握手，影响面被
 *    限制在用户明确打开开关的那一个目标上；
 *  - 同 host 不同端口视为两个目标（端口不同就是不同服务，证书通常也不同）。
 *
 * baseUrl 解析不出 host（空 / 缺 scheme）时**抛异常**而不是退回共享 trust-all ——
 * 配置错误必须响亮，不能静默降级成一个更宽的信任面。
 *
 * 引擎选择：trust-all 走 **Java 引擎**（JDK HttpClient 默认不做主机名校验，配合信任
 * 所有证书的 SSLContext 才能完整绕过链校验 + 主机名校验；CIO 的主机名校验在公开
 * API 里关不掉 —— 自签名证书 SAN 不含访问主机名/IP 时仅换 trustManager 仍会握手
 * 失败，见 Ktor 3.2.3 TLSClientHandshake 的字节码）。
 */
object AiHttp {

    private const val LOG_TAG = "ai-http"

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

    /**
     * 按连接取客户端：`ignoreSsl=false`（默认）走共享的严格客户端；
     * `true` 走**该连接目标 host 专属**的 trust-all 客户端。
     */
    fun clientFor(connection: AiConnection): HttpClient =
        if (connection.ignoreSsl) trustAllClientFor(connection) else strict.value

    /** 按 `host[:port]` 分桶的 trust-all 客户端（只有显式开启 ignoreSsl 的连接才会建）。 */
    private val trustAllClients = ConcurrentHashMap<String, HttpClient>()

    /**
     * 取（必要时创建）[connection] 目标 host 专属的 trust-all 客户端。
     *
     * 键由 [trustAllHostKey] 计算；解析不出 host 时抛 [AiException]。
     */
    private fun trustAllClientFor(connection: AiConnection): HttpClient {
        val key = trustAllHostKey(connection.baseUrl, connection.name)
        return trustAllClients.computeIfAbsent(key) {
            Log.warn(
                LOG_TAG,
                "creating trust-all TLS client for host='$key' (connection='${connection.name}', ignoreSsl=true)",
            )
            buildTrustAllClient()
        }
    }

    private fun buildTrustAllClient(): HttpClient {
        // 信任所有证书的 TrustManager：check* 全部空实现 —— 链校验由它接管。
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        return HttpClient(Java) {
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
     * trust-all 客户端的隔离键：`host` 或 `host:port`（有非默认端口时）。
     *
     * 解析不出来（空串 / 没有 scheme / 没有 host）直接抛 —— 这时**不能**退回共享
     * 客户端，否则一个坏配置就把别人的流量也送进了「不校验证书」的通道。
     */
    internal fun trustAllHostKey(baseUrl: String, connectionName: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) {
            throw AiException("连接「$connectionName」还没填接口地址（baseUrl），无法建立忽略 SSL 的连接")
        }
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
        val host = uri?.host?.takeIf { it.isNotBlank() }
            ?: throw AiException("连接「$connectionName」的 baseUrl 无法解析出主机名：$trimmed")
        val port = uri.port
        return if (port > 0) "$host:$port" else host
    }

    /**
     * 关闭全部引擎（严格 + 所有 trust-all）。由应用退出链（`shutdownApp`）调用。
     *
     * 为什么必须关：Ktor 的 CIO / Java 引擎各自持有 selector 线程与 dispatcher。
     * 桌面应用关窗后进程仍然活着（Nucleus 的窗口生命周期不等于 JVM 退出），这些
     * 线程就一直留着。虽然它们都是 daemon（不会阻止 JVM 退出），但重复开关窗口的
     * 场景里会看见线程数只增不减。
     *
     * **只关真正创建过的**（严格客户端用 `isInitialized`；trust-all 桶里有才关）：
     * 对从未用过的字段调 `close()` 会触发初始化再关 —— 白白起一批线程再拆掉。
     *
     * 幂等性：Ktor 的 `HttpClient.close()` 可以重复调（内部 engine 关闭是幂等的），
     * 这里再用 `runCatching` 包一层 —— 关闭失败不应把退出链后续步骤带走。
     */
    fun close() {
        // Lazy.isInitialized() 是函数（接口成员），不是委托属性的那个扩展属性。
        if (strict.isInitialized()) runCatching { strict.value.close() }
        trustAllClients.values.forEach { client -> runCatching { client.close() } }
        trustAllClients.clear()
    }
}
