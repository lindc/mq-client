package com.mqclient.core.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 部分扫描的缓存写入纪律（四个 MQ 模块共用）。
 *
 * 盯的是两个容易「静默退化」的判定：
 *  - 失败的类型不能拿空结果覆盖缓存；
 *  - 单类失败不能把整条连接判成已断开。
 */
class ScannedCacheTest {

    private enum class Kind { A, B, C }

    @Test
    fun `failed kind keeps the previous value`() {
        val cache = CacheManager()
        cache.put("a", listOf("上一轮"))
        val failures = mapOf(Kind.A to "boom")

        cache.putIfScanned("a", Kind.A, failures, emptyList<String>(), ttlMs = 60_000)

        assertEquals(listOf("上一轮"), cache.getAs<List<String>>("a"), "失败的类型不能被空结果覆盖")
    }

    @Test
    fun `scanned kind is replaced`() {
        val cache = CacheManager()
        cache.put("a", listOf("上一轮"))

        cache.putIfScanned("a", Kind.A, emptyMap(), listOf("新"), ttlMs = 60_000)

        assertEquals(listOf("新"), cache.getAs<List<String>>("a"))
    }

    @Test
    fun `other kinds failing does not block this kind`() {
        val cache = CacheManager()
        val failures = mapOf(Kind.B to "boom")

        cache.putIfScanned("a", Kind.A, failures, listOf("新"), ttlMs = 60_000)

        assertEquals(listOf("新"), cache.getAs<List<String>>("a"), "只管自己那一类，别人的失败不该拦我")
    }

    @Test
    fun `null cache is tolerated`() {
        // 连接断开的窗口里 cacheOf 可能为 null；不能因此抛异常。
        val cache: CacheManager? = null
        cache.putIfScanned("a", Kind.A, emptyMap(), "v", ttlMs = 1_000)
    }

    @Test
    fun `successful scan leaves the cache untouched by failures map`() {
        val cache = CacheManager()

        // 传一个「别人的失败」进去，确认写进去的是传入值而不是被空值顶掉。
        cache.putIfScanned("a", Kind.A, mapOf(Kind.C to "nope"), listOf("ok"), ttlMs = 60_000)

        assertEquals(listOf("ok"), cache.getAs<List<String>>("a"))
    }

    @Test
    fun `one kind failing is not a broken connection`() {
        assertFalse(
            allKindsFailed(setOf(Kind.A), Kind.entries),
            "单类失败是局部问题（常见于权限缺口），不该判连接已断",
        )
    }

    @Test
    fun `two of three failing is still not broken`() {
        assertFalse(allKindsFailed(setOf(Kind.A, Kind.B), Kind.entries))
    }

    @Test
    fun `all kinds failing is a broken connection`() {
        assertTrue(allKindsFailed(Kind.entries.toSet(), Kind.entries))
    }

    @Test
    fun `no failure is not a broken connection`() {
        assertFalse(allKindsFailed(emptySet<Kind>(), Kind.entries))
    }

    @Test
    fun `empty kind list never counts as broken`() {
        // 防回归：`failed.containsAll(emptyList())` 恒为 true，直接用会得到
        // 「什么类型都没扫 → 连接坏了」这种荒谬结论。
        assertFalse(allKindsFailed(emptyList<Kind>(), emptyList()), "空类型集合不该判成断开")
    }
}
