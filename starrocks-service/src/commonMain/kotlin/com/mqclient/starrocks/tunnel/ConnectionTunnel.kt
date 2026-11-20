package com.mqclient.starrocks.tunnel

import com.mqclient.starrocks.StarRocksConnectionConfig

/**
 * 连接前的可选**跳板**（SSH 隧道）。commonMain 只认识这个契约，JSch 的实现放在
 * jvmMain —— 与 `JdbcConnector` 同一隔离口径：模板层与 commonTest 不认识具体协议。
 *
 * 为什么需要一层接口而不是让 manager 直接调 JSch：`openSession` 在 commonMain，
 * 那里不能引 `com.jcraft.jsch`。抽成接口后：
 *  - 平台实现可替换（真实 SSH / 测试假实现）；
 *  - 「隧道先开、连不上就关」这段生命周期可以在 commonTest 里断言。
 */
interface ConnectionTunnel {

    /** JDBC 应连接的本地端口（隧道已在 127.0.0.1 上监听）。 */
    val localPort: Int

    /** 关闭隧道（必须幂等；异常自行吞掉，不能打断断开路径）。 */
    fun close()
}

/** 打开隧道。由 jvmMain 的 SSH 实现提供。 */
fun interface ConnectionTunnelFactory {

    /**
     * @throws Exception 建连 / 认证 / 端口转发失败。异常消息应可直接展示给用户
     *   （说清失败在堡垒机这一步，而不是让用户去查 SQL 权限）。
     */
    suspend fun open(config: StarRocksConnectionConfig): ConnectionTunnel
}

/**
 * 把 JDBC URL 的 authority（`host:port`）换成隧道本地端口。
 *
 * 三种形态都要处理，因为 `jdbcUrlOverride` 允许用户塞任意连接参数：
 * ```
 * jdbc:mysql://db.example:9030/sales?connectTimeout=3000
 *              ^^^^^^^^^^^^^^ 换成 127.0.0.1:41234
 * ```
 *
 * 保持其余部分**逐字节不变**（路径、查询参数、协议头）—— 隧道只该改连接地址，
 * 不该顺手重排参数（那会让用户的 `sessionVariables=` 等参数顺序变化）。
 *
 * 解析不走正则：URL 里可能出现 IPv6（`[::1]:9030`）与 IPv6 zone id，正则容易切错。
 *
 * @return 重写后的 URL；形状无法识别时返回 null（调用方据此报错，而不是静默连错）。
 */
fun rewriteJdbcAuthority(url: String, host: String = LOOPBACK, port: Int): String? {
    val schemeEnd = url.indexOf("://").takeIf { it >= 0 } ?: return null
    val authorityStart = schemeEnd + 3
    // authority 到第一个 `/` 或 `?` 为止（没有路径时 `?` 直接跟在后面）。
    val authorityEnd = url.indexOfAny(charArrayOf('/', '?'), authorityStart)
        .let { if (it < 0) url.length else it }

    val authority = url.substring(authorityStart, authorityEnd)
    if (authority.isEmpty()) return null

    // 丢掉 userinfo（`user:pass@`）—— 隧道连的是本地回环，凭据由 JDBC 参数传。
    val hostPort = authority.substringAfterLast('@')
    if (hostPort.isEmpty()) return null

    val newAuthority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
    return url.substring(0, authorityStart) + newAuthority + url.substring(authorityEnd)
}

/** 隧道本地监听地址（只绑回环，不对外暴露）。 */
const val LOOPBACK: String = "127.0.0.1"

/**
 * 当前平台没有隧道实现（`tunnelFactory` 为 null）却要求了 SSH。
 *
 * 单独类型而不是 NPE：这条消息会直接展示给用户，得说清"这个构建不支持"，
 * 而不是让用户以为配置填错了。
 */
class SshUnsupportedException :
    Exception("SSH tunnel is not available on this platform")
