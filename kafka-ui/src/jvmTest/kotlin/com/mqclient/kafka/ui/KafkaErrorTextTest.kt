package com.mqclient.kafka.ui

import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.apache.kafka.common.errors.GroupAuthorizationException
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.errors.TopicAuthorizationException
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException

/**
 * Kafka 错误文案的统一出口。
 *
 * 断言分两层，**都与语言表是否加载无关**：
 *  - **分类正确**：`kafkaErrorKey` 是纯函数（异常 → i18n key），不碰语言表。
 *    以前直接对 `kafkaErrorText` 的返回值断言 key 子串，隐含了「本测试跑在语言表
 *    未加载的环境里」这个前提 —— `LanguageResources` 是进程级单例，任何一个先
 *    `load()` 的测试类都会让后面的断言按执行顺序变红（实测踩到）。
 *  - **绝不泄漏原始类名**：无分类回退时走 `failureText`，不能出现 `org.apache.kafka`。
 *
 * 「原文片段要附上」这条单独断言，因为它是可操作性的来源（哪个 topic / 哪个 broker）。
 */
class KafkaErrorTextTest {

    @Test
    fun `timeout maps to the timeout key and keeps the detail`() {
        val error = TimeoutException("Topic orders not present in metadata after 60000 ms.")
        val text = kafkaErrorText(error)

        assertEquals("kafka_error_timeout", kafkaErrorKey(error))
        assertTrue(text.contains("orders"), "原文片段要保留（哪个对象超时），实际：$text")
        assertFalse(text.contains("org.apache.kafka"), "不能泄漏原始类名：$text")
    }

    @Test
    fun `execution wrapper is unwrapped before classification`() {
        assertEquals(
            "kafka_error_unknown_topic",
            kafkaErrorKey(ExecutionException(UnknownTopicOrPartitionException("orders"))),
        )
    }

    @Test
    fun `completion wrapper is unwrapped too`() {
        assertEquals(
            "kafka_error_record_too_large",
            kafkaErrorKey(CompletionException(RecordTooLargeException("too big"))),
        )
    }

    @Test
    fun `topic authorization wins over the generic authorization branch`() {
        assertEquals("kafka_error_authorization", kafkaErrorKey(TopicAuthorizationException(setOf("orders"))))
    }

    @Test
    fun `group authorization also maps`() {
        assertEquals("kafka_error_authorization", kafkaErrorKey(GroupAuthorizationException("cg-1")))
    }

    @Test
    fun `unknown exceptions fall back to their own message`() {
        // service 层已经写过中文可读文案的异常不该被重新包装。
        assertEquals("消费组不存在", kafkaErrorText(IllegalStateException("消费组不存在")))
    }

    @Test
    fun `fallback never returns blank`() {
        assertTrue(kafkaErrorText(IllegalStateException()).isNotBlank())
    }

    @Test
    fun `ssl failures are classified`() {
        assertEquals(
            "kafka_error_ssl",
            kafkaErrorKey(javax.net.ssl.SSLHandshakeException("certificate verify failed")),
        )
    }

    @Test
    fun `unclassified errors have no key and fall back to their own message`() {
        // 无 key 是「交给 failureText」的信号；这里同时钉住回退不吞消息。
        assertEquals(null, kafkaErrorKey(IllegalStateException("消费组不存在")))
        assertEquals("消费组不存在", kafkaErrorText(IllegalStateException("消费组不存在")))
    }
}
