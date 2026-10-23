package com.mqclient.uicommon

import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用户可见错误文案的统一入口。
 *
 * 之前 13 个对话框各有一份私有实现（只解 ExecutionException）；这里把行为钉住，
 * 尤其是 `CompletionException` —— `CompletableFuture` 异步链抛的就是它。
 */
class FailureTextTest {

    @Test
    fun `plain exception keeps its message`() {
        assertEquals("连接超时", failureText(IllegalStateException("连接超时")))
    }

    @Test
    fun `execution exception is unwrapped`() {
        val wrapped = ExecutionException(IllegalStateException("ACL 拒绝"))

        assertEquals("ACL 拒绝", failureText(wrapped))
    }

    @Test
    fun `completion exception is unwrapped`() {
        // 这是旧副本漏掉的那一类：异步链（thenApply 里抛）包的就是它。
        val wrapped = CompletionException(IllegalStateException("broker 不可用"))

        assertEquals("broker 不可用", failureText(wrapped))
    }

    @Test
    fun `blank message falls back to the class name`() {
        assertEquals("IllegalStateException", failureText(IllegalStateException("")))
        assertEquals("IllegalStateException", failureText(IllegalStateException("   ")))
    }

    @Test
    fun `null message falls back to the class name`() {
        assertEquals("IllegalStateException", failureText(IllegalStateException(null as String?)))
    }

    @Test
    fun `wrapper without cause keeps its own message`() {
        // cause 为 null 时不能变成 "null"，要退回包装自己的文案。
        assertEquals("future failed", failureText(ExecutionException("future failed", null)))
    }

    @Test
    fun `never returns a blank string`() {
        // 匿名类的 simpleName 是空串（不是 null）—— 旧实现在这里会返回 ""，
        // 而 UI 会把空串当成「没有错误」。要退到全限定名。
        val anonymous = object : RuntimeException(null as String?) {}

        val text = failureText(anonymous)

        assertTrue(text.isNotBlank(), "错误文案永远不能是空白，实际：『$text』")
    }
}
