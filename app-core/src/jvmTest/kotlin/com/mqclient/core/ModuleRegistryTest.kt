package com.mqclient.core

import java.util.ServiceConfigurationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * 覆盖 SPI 枚举的容错：`ServiceConfigurationError` 是 `Error`，`hasNext()` 和
 * `next()` 都会抛，而真实的配置损坏很难在测试里稳定复现 —— 这里用 [FlakyIterator]
 * 直接灌入故障。
 *
 * 另覆盖退出时的销毁顺序（[ModuleRegistry.disposeAll]）：应用 classpath 上的真实
 * SPI 注册在测试里是空的，所以按纯函数注入假适配器来断言。
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

    // ---------------------------------------------------------------- 销毁

    @Test
    fun `dispose runs in reverse initialization order`() {
        val disposed = mutableListOf<String>()
        val adapters = listOf(
            FakeAdapter("a") { disposed += "a" },
            FakeAdapter("b") { disposed += "b" },
            FakeAdapter("c") { disposed += "c" },
        )

        ModuleRegistry.disposeAll(adapters) { adapter, error -> fail("不该有失败：${adapter.id} $error") }

        assertEquals(listOf("c", "b", "a"), disposed, "后初始化的先销毁")
    }

    @Test
    fun `a module that throws on dispose does not stop the others`() {
        val disposed = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val adapters = listOf(
            FakeAdapter("a") { disposed += "a" },
            FakeAdapter("b") {
                disposed += "b"
                throw IllegalStateException("cannot close")
            },
            FakeAdapter("c") { disposed += "c" },
        )

        ModuleRegistry.disposeAll(adapters) { adapter, _ -> failures += adapter.id }

        assertEquals(listOf("c", "b", "a"), disposed, "抛错的那个也在链条里，后面的模块照常收尾")
        assertEquals(listOf("b"), failures, "失败交给回调上报，不中断循环")
    }

    /** 只关心 dispose 的假适配器：描述字段填默认值，initialize 不做事。 */
    private class FakeAdapter(
        override val id: String,
        private val onDispose: () -> Unit = {},
    ) : ServiceAdapter {
        override val name: String get() = id
        override val description: String get() = ""
        override val protocol: String get() = ""

        override fun dispose() = onDispose()
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
