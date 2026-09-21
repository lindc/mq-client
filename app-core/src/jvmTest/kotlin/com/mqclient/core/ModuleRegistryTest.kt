package com.mqclient.core

import com.mqclient.core.spi.ConfigMigration
import java.util.ServiceConfigurationError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

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
            ModuleRegistry.unknownUIServiceIds(known, listOf("ibm-mq", "kafk", "kafka")),
            "拼错的名字必须被指出来，其余不受影响",
        )
        assertEquals(
            listOf("ghost"),
            ModuleRegistry.unknownUIServiceIds(known, listOf("ghost", "ghost")),
            "重复的名字只报一次",
        )
        assertEquals(
            emptyList(),
            ModuleRegistry.unknownUIServiceIds(known, listOf("ibm-mq", "kafka")),
        )
    }

    // ---------------------------------------------------------------- 分组

    @Test
    fun `services are grouped by category keeping order within each group`() {
        val grouped = ModuleRegistry.groupByCategory(
            listOf(
                FakeAdapter("ibm-mq", category = "MQ"),
                FakeAdapter("kafka", category = "MQ"),
                FakeAdapter("openai-compatible", category = "AI"),
            ),
        )

        assertEquals(setOf("MQ", "AI"), grouped.keys)
        assertEquals(listOf("ibm-mq", "kafka"), grouped["MQ"]!!.map { it.id }, "组内保持传入顺序")
        assertEquals(listOf("openai-compatible"), grouped["AI"]!!.map { it.id })
    }

    @Test
    fun `empty service list groups to nothing`() {
        assertEquals(emptyMap(), ModuleRegistry.groupByCategory(emptyList()))
    }

    // ---------------------------------------------------------------- 配置迁移

    @Test
    fun `config migrations compose in order`() {
        val bump = ConfigMigration { it.copy(metadata = it.metadata.copy(version = it.metadata.version + 1)) }

        val migrated = ModuleRegistry.runConfigMigrations(Config(), listOf(bump, bump))

        assertEquals(3, migrated.metadata.version, "两个迁移依次作用：默认 1 → 2 → 3")
    }

    @Test
    fun `untouched config keeps its identity so nothing gets written`() {
        val config = Config()
        val noop = ConfigMigration { it }

        assertTrue(ModuleRegistry.runConfigMigrations(config, emptyList()) === config, "没有迁移时不该换对象")
        assertTrue(
            ModuleRegistry.runConfigMigrations(config, listOf(noop, noop)) === config,
            "迁移没改动时必须保住引用相等 —— core 靠它决定「一个字节都不写」",
        )
    }

    // ---------------------------------------------------------------- 销毁

    @Test
    fun `dispose runs in reverse initialization order`() {
        val disposed = mutableListOf<String>()
        val modules = modules(
            "a" to { disposed += "a" },
            "b" to { disposed += "b" },
            "c" to { disposed += "c" },
        )

        ModuleRegistry.disposeAll(modules) { adapter, error -> fail("不该有失败：${adapter.id} $error") }

        assertEquals(listOf("c", "b", "a"), disposed, "后初始化的先销毁")
        assertTrue(modules.all { it.second.isCancelled }, "每个模块的 scope 都要跟着取消")
    }

    @Test
    fun `a module that throws on dispose does not stop the others`() {
        val disposed = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val modules = modules(
            "a" to { disposed += "a" },
            "b" to {
                disposed += "b"
                throw IllegalStateException("cannot close")
            },
            "c" to { disposed += "c" },
        )

        ModuleRegistry.disposeAll(modules) { adapter, _ -> failures += adapter.id }

        assertEquals(listOf("c", "b", "a"), disposed, "抛错的那个也在链条里，后面的模块照常收尾")
        assertEquals(listOf("b"), failures, "失败交给回调上报，不中断循环")
        assertTrue(
            modules.all { it.second.isCancelled },
            "dispose 抛错的模块 scope 照样要取消 —— 否则它留下的订阅和定时任务全漏",
        )
    }

    /** 造「适配器 + 它的模块 scope」对：[ModuleRegistry.disposeAll] 收的就是这个。 */
    private fun modules(vararg specs: Pair<String, () -> Unit>): List<Pair<ServiceAdapter, CoroutineScope>> =
        specs.map { (id, onDispose) ->
            FakeAdapter(id, onDispose = onDispose) to CoroutineScope(SupervisorJob())
        }

    private val CoroutineScope.isCancelled: Boolean get() = coroutineContext[Job]?.isCancelled == true

    /** 只关心 dispose 的假适配器：描述字段填默认值，initialize 不做事。 */
    private class FakeAdapter(
        override val id: String,
        override val category: String = "MQ",
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
