package com.mqclient.rabbitmq

import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * 自签 TLS 的信任上下文：从 PKCS12 信任库加载 CA（与 Kafka 的 truststore 配置同
 * 一场景）。路径为空返回 null（调用方回落 JVM 默认信任链）；加载失败抛异常 ——
 * 配置错误必须让用户看到原因，而不是悄悄回落默认信任链导致握手失败难排查。
 */
internal fun rabbitMqSslContext(truststorePath: String, truststorePassword: String): SSLContext? {
    val path = truststorePath.trim()
    if (path.isEmpty()) return null
    val keyStore = KeyStore.getInstance("PKCS12")
    FileInputStream(path).use { keyStore.load(it, truststorePassword.toCharArray()) }
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(keyStore)
    return SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
}
