package com.mqclient.rocketmq

import com.mqclient.core.ServiceException
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.util.Base64

/**
 * JKS/P12 → PEM 转换层：RocketMQ 5.x Remoting 客户端（Netty `SslContextBuilder`）
 * 只吃 PEM 文件路径，而表单收的是与 Kafka / IBM MQ 同一套的 JKS/P12 证书库 ——
 * 这里负责加载证书库并把受信证书 / 私钥条目导出成 PEM 文本，由
 * [RocketMqAdminClient] 写进临时文件喂给客户端（close 时删除）。
 *
 * 私钥导出为**未加密 PKCS#8**（`PRIVATE KEY` 块）：keystore 密码只在加载时用，
 * 客户端侧 `tls.client.keyPassword` 保持空。
 */

/** 按扩展名解析 Keystore 类型：.p12/.pfx → PKCS12，其余（.jks 等）→ JKS（与 Kafka 表单同一规则）。 */
internal fun keystoreTypeFor(path: String): String {
    val lower = path.lowercase()
    return if (lower.endsWith(".p12") || lower.endsWith(".pfx")) "PKCS12" else "JKS"
}

/** 私钥条目的导出结果：证书链 PEM + 未加密 PKCS#8 私钥 PEM。 */
internal class ExportedKeyEntry(
    val certChainPem: String,
    val privateKeyPem: String,
)

/**
 * 导出 Truststore 中**全部受信证书**为 PEM 文本（每个证书一段 `CERTIFICATE` 块）。
 * 文件不存在 / 密码错 / 格式坏 → 抛带人类可读文案的 [IllegalStateException]。
 */
internal fun trustCertsToPem(path: String, password: String): String {
    val store = loadKeyStore(path, password)
    val pem = buildString {
        store.aliases().asSequence().forEach { alias ->
            store.getCertificate(alias)?.let { appendPemBlock(it.encoded, "CERTIFICATE") }
        }
    }
    if (pem.isBlank()) {
        throw ServiceException(
            "rocketmq_err_truststore_empty",
            args = listOf(path),
            fallbackMessage = "No certificates in the Truststore: %1\$s",
        )
    }
    return pem
}

/**
 * 导出 Keystore 中第一个私钥条目（证书链 + 私钥）。取 key 密码 = store 密码
 * （绝大多数客户端证书库的惯例）；条目不存在 / 密码不符 → 可读文案。
 */
internal fun exportKeyEntryToPem(path: String, storePassword: String): ExportedKeyEntry {
    val store = loadKeyStore(path, storePassword)
    val alias = store.aliases().toList().firstOrNull {
        store.getEntry(it, KeyStore.PasswordProtection(storePassword.toCharArray())) is KeyStore.PrivateKeyEntry
    } ?: throw ServiceException(
            "rocketmq_err_keystore_no_private_key",
            args = listOf(path),
            fallbackMessage = "No private key entry in the keystore (mutual TLS requires a keystore with a private key): %1\$s",
        )
    val entry =
        store.getEntry(alias, KeyStore.PasswordProtection(storePassword.toCharArray())) as KeyStore.PrivateKeyEntry
    val chain = entry.certificateChain
        ?: throw ServiceException(
                "rocketmq_err_keystore_no_cert_chain",
                args = listOf(path),
                fallbackMessage = "The private key entry has no certificate chain: %1\$s",
            )
    if (chain.isEmpty()) {
        throw ServiceException(
            "rocketmq_err_keystore_no_cert_chain",
            args = listOf(path),
            fallbackMessage = "The private key entry has no certificate chain: %1\$s",
        )
    }
    val certPem = chain.joinToString("") { pemBlock(it.encoded, "CERTIFICATE") }
    val keyPem = pemBlock(entry.privateKey.encoded, "PRIVATE KEY")
    return ExportedKeyEntry(certChainPem = certPem, privateKeyPem = keyPem)
}

/** 加载证书库；存在性 / 密码 / 格式错误全部转成人类可读文案（调用方直出 UI）。 */
private fun loadKeyStore(path: String, password: String): KeyStore {
    val file = File(path)
    if (!file.isFile) {
        throw ServiceException(
            "rocketmq_err_cert_file_missing",
            args = listOf(path),
            fallbackMessage = "Certificate file not found: %1\$s",
        )
    }
    val store = KeyStore.getInstance(keystoreTypeFor(path))
    try {
        file.inputStream().use { store.load(it, password.toCharArray()) }
    } catch (e: Exception) {
        val reason = e.message?.takeIf(String::isNotBlank) ?: e::class.simpleName ?: "unknown"
        throw ServiceException(
            "rocketmq_err_cert_load_failed",
            args = listOf(path, reason),
            fallbackMessage = "Failed to load the certificate file (check format and password): %1\$s (%2\$s)",
        ).initCause(e)
    }
    return store
}

/**
 * 文件是 PEM 编码证书（含 `CERTIFICATE` 块）时返回**原文件路径**，调用方直接把
 * 路径喂给客户端 `tls.client.trustCertPath` —— 公开证书无需转换、无需临时副本；
 * 是 PEM 但没有证书块（如只有私钥）→ 抛可读错误；不是 PEM → 返回 null（走
 * JKS/P12 加载）。
 */
internal fun pemCertOrNull(path: String): Path? {
    val file = File(path)
    if (!file.isFile) {
        throw ServiceException(
            "rocketmq_err_cert_file_missing",
            args = listOf(path),
            fallbackMessage = "Certificate file not found: %1\$s",
        )
    }
    if (file.length() > MAX_PEM_BYTES) {
        throw ServiceException(
            "rocketmq_err_cert_too_large",
            args = listOf(path),
            fallbackMessage = "Unexpected certificate file (over 1 MB): %1\$s",
        )
    }
    val text = file.readText(Charsets.US_ASCII)
    return when {
        text.contains("-----BEGIN CERTIFICATE-----") -> file.toPath()
        text.contains("-----BEGIN") ->
            throw ServiceException(
            "rocketmq_err_pem_no_certificate",
            args = listOf(path),
            fallbackMessage = "No certificate in the PEM file (private key only?); the Truststore needs a certificate file: %1\$s",
        )
        else -> null
    }
}

/** 快速判断文件是否 PEM 编码（读文件头找 BEGIN 标记）：Keystore 槽位只收二进制证书库。 */
internal fun isPemFile(path: String): Boolean {
    val file = File(path)
    if (!file.isFile) {
        throw ServiceException(
            "rocketmq_err_cert_file_missing",
            args = listOf(path),
            fallbackMessage = "Certificate file not found: %1\$s",
        )
    }
    val head = file.inputStream().use { input ->
        val buf = ByteArray(4096)
        val n = input.read(buf)
        String(buf, 0, maxOf(n, 0), Charsets.US_ASCII)
    }
    return head.contains("-----BEGIN")
}

/** PEM 块：label ∈ {CERTIFICATE, PRIVATE KEY}；DER 按 64 列 Base64 MIME 编码。 */
internal fun pemBlock(der: ByteArray, label: String): String = buildString {
    append("-----BEGIN ").append(label).append("-----\n")
    append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der))
    append("\n-----END ").append(label).append("-----\n")
}

private fun StringBuilder.appendPemBlock(der: ByteArray, label: String) {
    append(pemBlock(der, label))
}

/**
 * 把 PEM 文本写成临时文件（POSIX 600，只给当前用户读；非 POSIX 文件系统静默跳过
 * 权限设置）。返回路径由调用方登记，[deleteQuietly] 统一清理。
 */
internal fun writeTempPem(prefix: String, pem: String): Path {
    val file = Files.createTempFile(prefix, ".pem")
    Files.write(file, pem.toByteArray(Charsets.UTF_8))
    runCatching {
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
    }
    return file
}

/** 删临时 PEM（用完即毁；失败只吞掉 —— 清理路径不能被打断）。 */
internal fun deleteQuietly(path: Path?) {
    if (path == null) return
    runCatching { Files.deleteIfExists(path) }
}

/** PEM 文件大小上限：证书 / 证书链正常几 KB，超过 1MB 视为选错文件（防误读大文件）。 */
private const val MAX_PEM_BYTES = 1_000_000L
