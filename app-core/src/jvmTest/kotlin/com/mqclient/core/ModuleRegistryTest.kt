package com.mqclient.core

import java.util.ServiceConfigurationError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 覆盖 SPI 枚举的容错：`ServiceConfigurationError` 是 `Error`，`hasNext()` 和
 * `next()` 都会抛，而真实的配置损坏很难在测试里稳定复现 —— 这里用 [FlakyIterator]
 * 直接灌入故障。
 */
class ModuleRegistryTest {

    @Test
    fun `every healthy provider is returned`() {
        val result = loadFrom(FlakyIterator(hasNextAnswers = listOf(true, true, false), nextAnswers = listOf("a", "b")))

        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun `a provider that fails to load is skipped`() {
        val result = loadFrom(
            FlakyIterator(
                hasNextAnswers = listOf(true, true, true, false),
                nextAnswers = listOf("a", null, "c"),
            ),
        )

        assertEquals(listOf("a", "c"), result, "一个坏 provider 不该带走其它模块")
    }

    @Test
    fun `enumeration failure stops instead of hanging`() {
        val iterator = FlakyIterator(hasNextAnswers = emptyList(), nextAnswers = emptyList())

        assertEquals(emptyList(), loadFrom(iterator), "枚举失败返回已拿到的部分，不抛出")
        assertEquals(1, iterator.hasNextCalls, "同一个坏迭代器只会反复抛同一个错 —— 必须停")
    }

    @Test
    fun `providers already collected survive an enumeration failure`() {
        val iterator = FlakyIterator(hasNextAnswers = listOf(true, true), nextAnswers = listOf("a", "b"))

        assertEquals(listOf("a", "b"), loadFrom(iterator))
        assertEquals(3, iterator.hasNextCalls, "取完最后一个还要再问一次 hasNext，那次炸了就停")
    }

    @Test
    fun `ui service ids that match no service module are reported`() {
        val known = setOf("ibm-mq", "kafka")

        assertEquals(
            listOf("kafk"),
            ModuleRegistry.unknownUiServiceIds(known, listOf("ibm-mq", "kafk", "kafka")),
            "拼错的名字必须被指出来，其余不受影响",
        )
        assertEquals(
            listOf("ghost"),
            ModuleRegistry.unknownUiServiceIds(known, listOf("ghost", "ghost")),
            "重复的名字只报一次",
        )
        assertEquals(
            emptyList(),
            ModuleRegistry.unknownUiServiceIds(known, listOf("ibm-mq", "kafka")),
        )
    }

    private fun loadFrom(iterator: Iterator<String>): List<String> =
        ModuleRegistry.loadFrom(String::class.java) { iterator }

    /**
     * 答案用完即代表故障：模拟一个损坏的 provider 配置文件，之后每次调用都炸。
     * [nextAnswers] 里的 `null` 表示「这一个 provider 是坏的」。
     */
    private class FlakyIterator(
        private val hasNextAnswers: List<Boolean>,
        private val nextAnswers: List<String?>,
    ) : Iterator<String> {

        var hasNextCalls = 0
            private set

        private var nextCalls = 0

        override fun hasNext(): Boolean {
            val call = hasNextCalls++
            if (call >= hasNextAnswers.size) throw ServiceConfigurationError("cannot enumerate providers")
            return hasNextAnswers[call]
        }

        override fun next(): String {
            val call = nextCalls++
            val answer = if (call < nextAnswers.size) nextAnswers[call] else null
            return answer ?: throw ServiceConfigurationError("provider $call is broken")
        }
    }
}
