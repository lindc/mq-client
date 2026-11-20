package com.mqclient.starrocks.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.mqclient.core.log.LogLevel
import com.mqclient.starrocks.StarRocksConnectionConfig
import com.mqclient.starrocks.log.StarRocksLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 到 StarRocks FE 的**本地端口转发**（SSH 隧道）。
 *
 * ## 为什么需要它
 *
 * 生产环境的 StarRocks 几乎不会把 9030 直接暴露在公网，客户端只能经堡垒机访问。
 * 没有隧道时，这个客户端在生产场景下**连不上** —— 这不是体验问题，是可用性问题。
 *
 * ## 为什么用本地转发（`-L`）而不是跳板直连
 *
 * 本地转发只在 127.0.0.1 上开一个随机端口，由 SSH 会话把流量转到目标；
 * JDBC 驱动完全不知道隧道的存在（URL 指向 `127.0.0.1:<port>`）。这样：
 *  - 不需要给 MySQL 驱动做任何适配；
 *  - 隧道断开会表现为普通的连接错误，走既有的错误分类与重连路径；
 *  - 监听口绑定回环地址，不会把数据库端口暴露给同网段其它机器。
 *
 * ## 生命周期
 *
 * 与 JDBC 连接**同生共死**：`StarRocksConnectionManager.openSession` 建隧道 →
 * 连接成功后把隧道挂在 `JdbcConnection` 的关闭路径上。连接关、隧道关；
 * 任何一步失败都在本类的调用方 finally 里被清理。
 */
internal class SshTunnel private constructor(
    private val session: Session,
    /** 本地监听端口（JDBC URL 应指向 `127.0.0.1:localPort`）。 */
    override val localPort: Int,
) : com.mqclient.starrocks.tunnel.ConnectionTunnel {

    /** 关闭会话（幂等；异常只记日志，不打断断开路径）。 */
    override fun close() {
        runCatching { session.disconnect() }
            .onFailure {
                StarRocksLog.conn(
                    LogLevel.DEBUG,
                    "ssh tunnel disconnect failed (ignored): ${it.message}",
                )
            }
    }

    companion object {

        /** 本地监听的固定回环地址（不绑 0.0.0.0，见类文档）。 */
        private const val LOCAL_BIND = "127.0.0.1"

        /** 隧道建连超时（含 TCP + 认证）。堡垒机不可达时不该让表单卡住几十秒。 */
        private const val CONNECT_TIMEOUT_MS = 15_000

        /**
         * 打开隧道，返回本地监听端口。
         *
         * @param config 连接配置（提供堡垒机地址与凭据，以及要转发的目标 host/port）
         * @throws SshTunnelException 建连 / 认证 / 端口转发失败（消息可直接给用户看）
         */
        suspend fun open(
            config: StarRocksConnectionConfig,
            // 端口要传给仓库：JSch 的 check() 用 `[host]:port` 作为主机标识。
            knownHosts: KnownHostsStore = KnownHostsStore(port = config.sshPort),
        ): SshTunnel = withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            StarRocksLog.conn(
                "ssh tunnel start jump=${config.sshUser}@${config.sshHost}:${config.sshPort} " +
                    "target=${config.host}:${config.port} auth=${if (config.sshUsesKey) "key" else "password"}",
            )
            try {
                val session = JSch().let { jsch ->
                    // 私钥认证（sshPassword 此时是 passphrase）。路径不存在直接报错，
                    // 不要退化成密码认证 —— 那会让用户以为"密码错了"，实际是找错文件。
                    if (config.sshUsesKey) {
                        val key = File(config.sshKeyPath.removePrefix("~").let {
                            if (config.sshKeyPath.startsWith("~")) {
                                System.getProperty("user.home") + it
                            } else {
                                config.sshKeyPath
                            }
                        })
                        if (!key.isFile) {
                            throw SshTunnelException("SSH 私钥文件不存在：${config.sshKeyPath}")
                        }
                        jsch.addIdentity(key.absolutePath, config.sshPassword.ifBlank { null })
                    }
                    val s = jsch.getSession(config.sshUser, config.sshHost, config.sshPort)
                    if (!config.sshUsesKey) s.setPassword(config.sshPassword)
                    s.timeout = CONNECT_TIMEOUT_MS
                    s
                }
                // 主机密钥校验（TOFU）：未知主机 → 抛 SshUnknownHostException 让 UI 确认；
                // 已变更 → 抛 SshHostKeyChangedException 直接拒绝。
                // 不用 StrictHostKeyChecking=no —— 那等于任何能拦到流量的人都能冒充堡垒机。
                // 注意 JSch 只在 connect() 时读这份配置，所以设置必须在这里、且只在这里。
                session.hostKeyRepository = knownHosts
                session.setConfig("StrictHostKeyChecking", "yes")
                session.connect(CONNECT_TIMEOUT_MS)

                // 端口 0 = 让系统挑一个空闲端口，避免固定端口冲突；
                // 监听地址显式给 127.0.0.1（JSch 的 sess 参数即绑定地址）。
                @Suppress("DEPRECATION")
                val port = session.setPortForwardingL(LOCAL_BIND, 0, config.host, config.port)

                StarRocksLog.conn(
                    "ssh tunnel ready localPort=$port target=${config.host}:${config.port} " +
                        "cost=${System.currentTimeMillis() - start}ms",
                )
                StarRocksLog.perf(
                    action = "ssh_tunnel",
                    type = "CONNECT",
                    costMs = System.currentTimeMillis() - start,
                )
                SshTunnel(session, port)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (error is Error) throw error
                StarRocksLog.conn(
                    LogLevel.ERROR,
                    "ssh tunnel failed jump=${config.sshUser}@${config.sshHost}:${config.sshPort} " +
                        "cost=${System.currentTimeMillis() - start}ms errorType=${error::class.simpleName} " +
                        "detail=${error.message}",
                    error,
                )
                throw when (error) {
                    is SshTunnelException -> error
                    // JSch 在 StrictHostKeyChecking=yes 下对未知/变更主机抛 JSchException，
                    // 消息里带主机指纹。翻译成我们的类型，UI 才能区分「首次连接要确认」
                    // 与「密钥变了要警惕」——两者的处理完全不同。
                    is com.jcraft.jsch.JSchException -> translateHostKeyError(error, config, knownHosts)
                    // JSch 的异常消息对用户没意义（"Auth fail"），补上"卡在哪一步"。
                    else -> SshTunnelException(
                        "SSH 隧道建立失败（${config.sshHost}:${config.sshPort}）：${error.message ?: error::class.simpleName}",
                        error,
                    )
                }
            }
        }
    }
}

/**
 * 把 JSch 的主机密钥错误翻译成可操作的类型。
 *
 * **按异常类型判断，不解析消息文本**：JSch 2.28 有专门的分支
 * （`JSchUnknownHostKeyException` / `JSchChangedHostKeyException`），而消息
 * 文本只有一句 `reject HostKey: [127.0.0.1]:2223` —— 既没有指纹也没有 key 类型
 * （实测）。我最初按消息正则匹配，结果两类错误都被判成 UNKNOWN 且指纹为空。
 *
 * 指纹也拿不到 —— JSch 报错时不带。所以 UI 层要展示指纹时，由首连确认流程
 * 单独探测（见 [probeHostKey]）。
 */
private fun translateHostKeyError(
    error: com.jcraft.jsch.JSchException,
    config: StarRocksConnectionConfig,
    knownHosts: KnownHostsStore,
): Throwable = when (error) {
    is com.jcraft.jsch.JSchUnknownHostKeyException ->
        // 指纹从 check() 的副产物取（B 端公钥在密钥交换时已经过 check）。
        SshUnknownHostException(
            config.sshHost,
            keyType = "unknown",
            fingerprint = knownHosts.lastSeenFingerprint(config.sshHost).orEmpty(),
        )
    is com.jcraft.jsch.JSchChangedHostKeyException ->
        SshHostKeyChangedException(
            config.sshHost,
            keyType = "unknown",
            fingerprint = knownHosts.lastSeenFingerprint(config.sshHost).orEmpty(),
        )
    else -> SshTunnelException(
        "SSH 隧道建立失败（${config.sshHost}:${config.sshPort}）：${error.message}",
        error,
    )
}

/**
 * 隧道建立失败。
 *
 * 单独的类型而不是复用 `JdbcFailure`：失败发生在**连接数据库之前**，把它当成
 * SQL 错误会让用户去查 SQL / 权限，而真正的问题是堡垒机。
 */
class SshTunnelException(message: String, cause: Throwable? = null) : Exception(message, cause)
