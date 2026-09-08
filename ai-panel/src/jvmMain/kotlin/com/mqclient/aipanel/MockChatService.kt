package com.mqclient.aipanel

import com.mqclient.core.AiConfig
import kotlinx.coroutines.delay

/**
 * Mock backend used until a real provider is wired in.
 *
 * Picks one of three canned replies based on simple keyword sniffing in the
 * most recent user message, then streams it character-by-character so the
 * UI exercises the same code path a real SSE implementation would use.
 */
class MockChatService(
    private val chunkDelayMs: Long = 18L,
    private val chunkSize: Int = 3,
) : ChatService {

    override suspend fun streamReply(
        history: List<AiMessage>,
        config: AiConfig,
        context: AiContext?,
        onChunk: (String) -> Unit,
    ) {
        val lastUser = history.lastOrNull { it.role == Role.USER }?.content.orEmpty()
        val reply = pickReply(lastUser, context)
        stream(reply, onChunk)
    }

    private fun pickReply(userText: String, context: AiContext?): String {
        val lower = userText.lowercase()
        val contextLine = if (context?.connectionName != null) {
            "当前上下文: ${context.connectionName}" +
                (context.serviceId?.let { " ($it)" }.orEmpty()) +
                (context.objectType?.let { " · ${it}" }.orEmpty())
        } else {
            "当前未选中连接,只能给出通用建议。"
        }
        return when {
            "堆积" in userText || "backlog" in lower || "lag" in lower -> """
                $contextLine

                根据常见 MQ 排查经验,消息堆积通常按下面的顺序定位:

                1. 确认消费者数量与在线状态 — 看看是不是实例挂了或被缩容到 0。
                2. 看消费速率 — 单位时间消费条数是否低于生产速率。
                3. 检查单条消息处理耗时 — 慢调用会拉低整体吞吐。
                4. 看死信/重试队列 — 反复重试的消息可能把消费者卡住。
                5. 核对分区/队列绑定 — 路由键错配会导致消息堆积到错误的队列。

                如果你能贴出当前消费者数量和最近一分钟的入队/出队速率,我可以进一步定位。
            """.trimIndent()

            "连接" in userText || "connect" in lower || "timeout" in lower -> """
                $contextLine

                连接类问题先按这几步排查:

                - 用 telnet/nc 验证 host:port 是否可达,排掉网络与防火墙。
                - 检查服务端的用户名/凭据,尤其是 vhost 或 ACL 改动后是否同步。
                - 看客户端日志里的认证失败码:403 通常是权限,401 是凭据,530/531 是协议版本不匹配。
                - 如果是 TLS,确认证书链是否完整、是否启用了 SNI。

                修好之后建议在面板里把连接保存为命名条目,下次一键复用。
            """.trimIndent()

            else -> """
                $contextLine

                收到。我可以帮你:

                - 排查消息堆积、消费滞后、连接失败等常见问题。
                - 解释 Kafka / RabbitMQ / IBM MQ / RocketMQ / Artemis 的概念差异。
                - 生成安全的运维命令(创建队列、调优参数、查看统计)。

                描述一下你看到的现象,或者贴一段日志/指标,我来给你定位思路。
            """.trimIndent()
        }
    }

    private suspend fun stream(text: String, onChunk: (String) -> Unit) {
        var i = 0
        while (i < text.length) {
            val end = (i + chunkSize).coerceAtMost(text.length)
            onChunk(text.substring(i, end))
            i = end
            if (i < text.length) delay(chunkDelayMs)
        }
    }
}