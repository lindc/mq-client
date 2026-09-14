package com.mqclient.ai.adapter

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 从 SSE 响应体里逐条抽取 `data:` 载荷。
 *
 * OpenAI 兼容协议的流式响应只用单行 `data:`，
 * 所以不做多行拼接：跳过事件名（`event:`）、注释（`:` 开头的心跳行）与空行，
 * 通道关闭即流结束。
 */
internal fun ByteReadChannel.sseData(): Flow<String> = flow {
    while (true) {
        val line = readUTF8Line() ?: break
        if (!line.startsWith("data:")) continue
        // trim 顺带去掉部分服务端 \r\n 行尾残留的 \r。
        val payload = line.removePrefix("data:").trim()
        if (payload.isNotEmpty()) emit(payload)
    }
}
