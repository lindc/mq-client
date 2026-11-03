package com.mqclient.rocketmq

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.rocketmq.remoting.netty.TlsSystemConfig

/**
 * 守住 [RocketMqTlsStart] 的**全局 TLS 窗口互斥**。
 *
 * 背景：`TlsSystemConfig` 的证书字段是进程级静态字段，客户端只在 `start()` 内部
 * （`NettyRemotingClient` 构造 → `TlsHelper.buildSslContext`）读取它们。窗口不互斥时，
 * 两条不同证书的连接并发 start 会互相覆盖字段 —— 先进入的那条握手可能用上后者的 CA
 * （表现是「明明配了证书却握不上」，且只在多连接并发时复现）。
 *
 * 这里不连真集群：把「窗口互斥」与「全局字段被还原」当作可离线断言的两条契约。
 * `tlsVerifyServer = false` 让 [RocketMqTlsStart] 不需要真实证书文件，但窗口照旧生效
 * （`TlsPaths` 非空，走 apply → block → restore 全流程）。
 */
class RocketMqTlsStartTest {

    private fun tlsConfig() = RocketMqConfig(
        namesrvAddr = "127.0.0.1:9876",
        // TLS 开着但关掉服务器校验：没有临时 PEM，窗口仍然生效。
        tlsEnabled = true,
        tlsVerifyServer = false,
    )

    @Test
    fun `start window is exclusive across instances`() = runBlocking {
        val originals = GlobalTlsState.capture()
        val subjects = List(PARALLEL) { RocketMqTlsStart(tlsConfig()) }

        val inside = AtomicInteger(0)
        val maxInside = AtomicInteger(0)
        val entered = AtomicInteger(0)

        try {
            subjects.map { tls ->
                async(Dispatchers.IO) {
                    tls.start {
                        entered.incrementAndGet()
                        val now = inside.incrementAndGet()
                        // 记录历史最大并发数：> 1 就说明两条连接同时待在窗口里。
                        maxInside.updateAndGet { maxOf(it, now) }
                        // 留在窗口里一会儿，给并发的其他实例一个「撞进来」的机会。
                        Thread.sleep(WINDOW_HOLD_MS)
                        // 窗口内必须已经写入本连接的证书配置（tlsTestModeEnable=false
                        // 是 applyTls 的固定动作；默认值是 true = 信任所有证书）。
                        assertTrue(
                            !TlsSystemConfig.tlsTestModeEnable,
                            "窗口内应已关闭 tlsTestModeEnable（否则等于不校验服务端）",
                        )
                        assertTrue(
                            !TlsSystemConfig.tlsClientAuthServer,
                            "窗口内应已按连接配置写入 tlsClientAuthServer",
                        )
                        inside.decrementAndGet()
                    }
                }
            }.awaitAll()
        } finally {
            subjects.forEach { it.close() }
            GlobalTlsState.restore(originals)
        }

        assertEquals(PARALLEL, entered.get(), "每个实例的 start 都必须真正执行一次")
        assertEquals(1, maxInside.get(), "任意时刻只允许一个实例待在 TLS 全局窗口里")
    }

    @Test
    fun `global tls fields are restored after the window`() {
        val originals = GlobalTlsState.capture()
        val subject = RocketMqTlsStart(tlsConfig())
        try {
            runBlocking { subject.start { } }
        } finally {
            subject.close()
        }
        assertEquals(originals, GlobalTlsState.capture(), "窗口结束后全局 TLS 字段必须原样还原")
    }

    /** `TlsSystemConfig` 里被 `applyTls` 改写的字段快照（断言「还原」用）。 */
    private data class GlobalTlsState(
        val testMode: Boolean,
        val authServer: Boolean,
        val trustCertPath: String?,
        val certPath: String?,
        val keyPath: String?,
        val keyPassword: String?,
        val configFile: String,
    ) {
        companion object {
            fun capture() = GlobalTlsState(
                testMode = TlsSystemConfig.tlsTestModeEnable,
                authServer = TlsSystemConfig.tlsClientAuthServer,
                trustCertPath = TlsSystemConfig.tlsClientTrustCertPath,
                certPath = TlsSystemConfig.tlsClientCertPath,
                keyPath = TlsSystemConfig.tlsClientKeyPath,
                keyPassword = TlsSystemConfig.tlsClientKeyPassword,
                configFile = TlsSystemConfig.tlsConfigFile,
            )

            fun restore(state: GlobalTlsState) {
                TlsSystemConfig.tlsTestModeEnable = state.testMode
                TlsSystemConfig.tlsClientAuthServer = state.authServer
                TlsSystemConfig.tlsClientTrustCertPath = state.trustCertPath
                TlsSystemConfig.tlsClientCertPath = state.certPath
                TlsSystemConfig.tlsClientKeyPath = state.keyPath
                TlsSystemConfig.tlsClientKeyPassword = state.keyPassword
                TlsSystemConfig.tlsConfigFile = state.configFile
            }
        }
    }

    private companion object {
        /** 并发实例数：足够让「不互斥」的实现稳定地撞在一起。 */
        const val PARALLEL = 8

        /** 每个实例在窗口里停留的时间（毫秒）。 */
        const val WINDOW_HOLD_MS = 40L
    }
}
