package com.mqclient.kafka.ui

import com.mqclient.uicommon.i18n.userMessage
import com.mqclient.uicommon.i18n.stringResource
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.ClusterAuthorizationException
import org.apache.kafka.common.errors.GroupAuthorizationException
import org.apache.kafka.common.errors.InvalidPartitionsException
import org.apache.kafka.common.errors.InvalidReplicationFactorException
import org.apache.kafka.common.errors.OffsetOutOfRangeException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.errors.TopicAuthorizationException
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import org.apache.kafka.common.errors.UnsupportedVersionException

/**
 * Kafka 异常 → 用户可读文案（kafka-ui 的唯一出口）。
 *
 * 与 IBM MQ 侧 `MqrcTranslator` 同样的职责，但落在 UI 模块：Kafka 的异常类型来自
 * `kafka-clients`（kafka-ui 本来就依赖它做实时查询），翻译结果又是**界面文案**，
 * 走 i18n 表才是正确的归属（中英双语，跟随应用语言切换）。
 *
 * 为什么必须翻译：`org.apache.kafka.common.errors.TimeoutException: Topic
 * orders not present in metadata after 60000 ms.` 这类原文直接甩给用户，
 * 既看不懂（英文 + 类名），也不知道该改什么（连不上？权限？topic 拼错？）。
 * 同一个异常类别在四个面板里出现，文案必须一致 —— 这里是唯一实现。
 *
 * 分类策略（先解包、再按类型、最后才回退原文）：
 *  1. `ExecutionException` / `CompletionException` 解一层 —— Kafka 的 `KafkaFuture`
 *     抛的就是它们；
 *  2. 命中已知类型 → i18n 文案（+ 原文关键片段，保留「哪个 topic / 哪个 broker」这类
 *     可操作细节）；
 *  3. 未命中 → [userMessage]（service 层已经写过中文可读文案的异常原样透出）。
 *
 * 注意顺序：`TopicAuthorizationException` 继承 `AuthorizationException`，
 * `SaslAuthenticationException` / `SslAuthenticationException` 继承
 * `AuthenticationException` —— 判断父类比子类晚，否则会被父类吃掉。
 */
fun kafkaErrorText(error: Throwable): String {
    val root = unwrapCompletion(error)
    val mapped = kafkaErrorKeyFor(root) ?: return root.userMessage()

    val base = stringResource(mapped)
    // 命中分类后仍附上原文片段：分类说明「哪一类问题」，原文说明「哪个对象」。
    val detail = root.message?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
    return if (detail != null) "$base — $detail" else base
}

/**
 * 分类本身：异常 → i18n key；未命中返回 null（调用方回退 [userMessage]）。
 *
 * 拆出来是**为了可测**：`kafkaErrorText` 的返回值依赖语言表是否加载（缺键时返回
 * key 本身，加载后返回中文），而 `LanguageResources` 是进程级单例 —— 一旦有别的
 * 测试类先加载了表，断言 key 的用例就会因为执行顺序而红。分类与文案拼装分开之后，
 * 断言落在分类上，与语言表状态无关。
 */
internal fun kafkaErrorKey(error: Throwable): String? = kafkaErrorKeyFor(unwrapCompletion(error))

/** 解掉 KafkaFuture 的两层包装（`ExecutionException` / `CompletionException`）。 */
internal fun unwrapCompletion(error: Throwable): Throwable = when (error) {
    is ExecutionException -> error.cause ?: error
    is CompletionException -> error.cause ?: error
    else -> error
}

private fun kafkaErrorKeyFor(unwrapped: Throwable): String? =
    when (unwrapped) {
        // 子类优先：这三个都是 AuthorizationException 的子类。
        is TopicAuthorizationException -> "kafka_error_authorization"
        is GroupAuthorizationException -> "kafka_error_authorization"
        is ClusterAuthorizationException -> "kafka_error_authorization"
        is AuthorizationException -> "kafka_error_authorization"
        // SaslAuthenticationException / SslAuthenticationException 是它的子类。
        is AuthenticationException -> "kafka_error_auth"
        is TimeoutException -> "kafka_error_timeout"
        is UnknownTopicOrPartitionException -> "kafka_error_unknown_topic"
        is RecordTooLargeException -> "kafka_error_record_too_large"
        is OffsetOutOfRangeException -> "kafka_error_offset_out_of_range"
        is TopicExistsException -> "kafka_error_topic_exists"
        is InvalidReplicationFactorException -> "kafka_error_invalid_topic_config"
        is InvalidPartitionsException -> "kafka_error_invalid_topic_config"
        is UnsupportedVersionException -> "kafka_error_unsupported_version"
        else -> if (unwrapped.isSslFailure()) "kafka_error_ssl" else null
    }

/** SSL 握手类失败（`javax.net.ssl` 的异常在 Kafka 里不包装成自有类型）。 */
private fun Throwable.isSslFailure(): Boolean =
    generateSequence(this as Throwable?) { it.cause }
        .take(6)
        .any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }
