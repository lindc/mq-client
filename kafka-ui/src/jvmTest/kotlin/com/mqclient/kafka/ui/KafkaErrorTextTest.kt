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
 * 测试跑在没加载语言表的环境里（`stringResource` 缺键返回 key 本身），所以断言
 * 分两类：
 *  - **分类正确**：异常类型 → 对应的 i18n key（这是本函数的职责，与语言无关）；
 *  - **绝不泄漏原始类名**：无分类回退时走 `failureText`，也不能出现
 *    `org.apache.kafka` 前缀。
 *
 * 「原文片段要附上」这条单独断言，因为它是可操作性的来源（哪个 topic / 哪个 broker）。
 */
class KafkaErrorTextTest {

    @Test
    fun `timeout maps to the timeout key and keeps the detail`() {
        val text = kafkaErrorText(
            TimeoutException("Topic orders not present in metadata after 60000 ms."),
        )

        assertTrue(text.contains("kafka_error_timeout"), "应命中超时分类，实际：$text")
        assertTrue(text.contains("orders"), "原文片段要保留（哪个对象超时），实际：$text")
        assertFalse(text.contains("org.apache.kafka"), "不能泄漏原始类名：$text")
    }

    @Test
    fun `execution wrapper is unwrapped before classification`() {
        val text = kafkaErrorText(ExecutionException(UnknownTopicOrPartitionException("orders")))

        assertTrue(text.contains("kafka_error_unknown_topic"), "应解包后命中分类，实际：$text")
    }

    @Test
    fun `completion wrapper is unwrapped too`() {
        val text = kafkaErrorText(CompletionException(RecordTooLargeException("too big")))

        assertTrue(text.contains("kafka_error_record_too_large"), "实际：$text")
    }

    @Test
    fun `topic authorization wins over the generic authorization branch`() {
        val text = kafkaErrorText(TopicAuthorizationException(setOf("orders")))

        assertTrue(text.contains("kafka_error_authorization"), "实际：$text")
    }

    @Test
    fun `group authorization also maps`() {
        val text = kafkaErrorText(GroupAuthorizationException("cg-1"))

        assertTrue(text.contains("kafka_error_authorization"), "实际：$text")
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
        val text = kafkaErrorText(javax.net.ssl.SSLHandshakeException("certificate verify failed"))

        assertTrue(text.contains("kafka_error_ssl"), "实际：$text")
    }
}
