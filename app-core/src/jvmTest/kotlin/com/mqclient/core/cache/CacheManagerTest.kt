package com.mqclient.core.cache

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CacheManager 的行为契约测试：
 *  - put / get / getAs 的读写与类型转换（类型不符返回 null，不抛异常）；
 *  - TTL 过期 + 惰性删除（读取时顺手清掉，不需要扫描线程）；
 *  - ttlMs = 0 永不过期；
 *  - remove / removeByPrefix（字面前缀，点边界不误伤）/ clear；
 *  - lastUpdatedAt 与 isFresh 的新鲜度语义（从没写过 = 不新鲜）；
 *  - 空白 key / 前缀被拒绝；
 *  - update / updateIfPresent 的原子读-改-写语义（含「原样返回 = 不动」）；
 *  - 多线程并发写入不丢条目（线程安全冒烟）。
 *
 * 时间相关用例沿用 JobSchedulerTest 的写法：短 TTL + 宽松等待，不做「精确到毫秒」
 * 的假设。
 */
class CacheManagerTest {

    @Test
    fun `put then get round-trips the value`() {
        val cache = CacheManager()
        cache.put("queues", listOf("a", "b"))
        assertEquals(listOf("a", "b"), cache.get("queues"))
        assertNull(cache.get("missing"))
    }

    @Test
    fun `getAs returns typed value and null on type mismatch`() {
        val cache = CacheManager()
        cache.put("count", 3)
        assertEquals(3, cache.getAs<Int>("count"))
        assertNull(cache.getAs<String>("count"), "类型不符应该返回 null 而不是抛 ClassCastException")
    }

    @Test
    fun `expired entry returns null and is removed lazily`() {
        val cache = CacheManager()
        cache.put("short", "v", ttlMs = 200L)

        assertEquals("v", cache.get("short"), "TTL 未到，应该还能读到")
        Thread.sleep(400L)
        assertNull(cache.get("short"))
        assertEquals(0, cache.size(), "过期条目应该在读取时被惰性删掉")
    }

    @Test
    fun `ttl zero means never expires`() {
        val cache = CacheManager()
        cache.put("forever", "v")
        Thread.sleep(50L)
        assertEquals("v", cache.get("forever"))
    }

    @Test
    fun `remove deletes a single key`() {
        val cache = CacheManager()
        cache.put("a", 1)
        cache.put("b", 2)
        cache.remove("a")
        assertNull(cache.get("a"))
        assertEquals(2, cache.get("b"))
    }

    @Test
    fun `removeByPrefix matches literally so the dot boundary matters`() {
        val cache = CacheManager()
        cache.put("queue.a.one", 1)
        cache.put("queue.a.two", 2)
        cache.put("queue.ab.one", 3) // 不该被 "queue.a." 误伤
        cache.put("queues", 4)

        cache.removeByPrefix("queue.a.")

        assertNull(cache.get("queue.a.one"))
        assertNull(cache.get("queue.a.two"))
        assertEquals(3, cache.get("queue.ab.one"))
        assertEquals(4, cache.get("queues"))
    }

    @Test
    fun `clear resets everything including freshness`() {
        val cache = CacheManager()
        cache.put("a", 1)
        assertTrue(cache.isFresh(60_000L))

        cache.clear()

        assertEquals(0, cache.size())
        assertEquals(emptyList<String>(), cache.keys())
        assertEquals(0L, cache.getLastUpdatedAt())
        assertFalse(cache.isFresh(60_000L), "清空后不该再算新鲜")
        assertNull(cache.get("a"))
    }

    @Test
    fun `isFresh reflects the window and the never-written state`() {
        val cache = CacheManager()
        assertFalse(cache.isFresh(60_000L), "从没写过永远算不新鲜")
        assertEquals(0L, cache.getLastUpdatedAt())

        cache.put("a", 1)
        assertTrue(cache.isFresh(60_000L))
    }

    @Test
    fun `lastUpdatedAt advances on every put`() {
        val cache = CacheManager()
        cache.put("a", 1)
        val first = cache.getLastUpdatedAt()
        assertTrue(first > 0L)

        Thread.sleep(30L)
        cache.put("b", 2)
        assertTrue(cache.getLastUpdatedAt() > first, "新的 put 应该刷新整体更新时间")
    }

    @Test
    fun `blank keys and prefixes are rejected`() {
        val cache = CacheManager()
        assertFailsWith<IllegalArgumentException> { cache.put("", 1) }
        assertFailsWith<IllegalArgumentException> { cache.put("   ", 1) }
        assertFailsWith<IllegalArgumentException> { cache.removeByPrefix("") }
    }

    @Test
    fun `concurrent puts do not lose entries`() {
        val cache = CacheManager()
        val threads = 4
        val perThread = 200
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> cache.put("k-$t-$i", i) }
                done.countDown()
            }
        }
        start.countDown()

        assertTrue(done.await(10, TimeUnit.SECONDS), "并发写入超时")
        pool.shutdown()
        assertEquals(threads * perThread, cache.size(), "并发写入不该丢条目")
    }

    /* ------------------------- update / updateIfPresent ------------------------- */

    @Test
    fun `update creates the entry when the key is absent`() {
        val cache = CacheManager()

        val changed = cache.updateAs<List<String>>("queues") { current -> (current ?: emptyList()) + "a" }

        assertTrue(changed)
        assertEquals(listOf("a"), cache.getAs<List<String>>("queues"))
    }

    @Test
    fun `update hands the transform a null for a missing key`() {
        val cache = CacheManager()
        var seen: Any? = "sentinel"

        cache.update("k") { current ->
            seen = current
            "v"
        }

        assertNull(seen, "key 不存在时 transform 应该拿到 null")
        assertEquals("v", cache.get("k"))
    }

    @Test
    fun `update with null removes the entry`() {
        val cache = CacheManager()
        cache.put("k", "v")

        assertTrue(cache.update("k") { null })

        assertNull(cache.get("k"))
        assertEquals(0, cache.size())
    }

    @Test
    fun `update returning the same instance leaves the entry untouched`() {
        val cache = CacheManager()
        val value = listOf("a")
        cache.put("k", value)
        val before = cache.getLastUpdatedAt()

        val changed = cache.update("k") { current -> current }

        assertFalse(changed, "原样返回 = 不动，不应报告写入")
        assertSame(value, cache.get("k"), "原样返回不应重建条目")
        assertEquals(before, cache.getLastUpdatedAt(), "没改动就不该刷新整体新鲜度")
    }

    @Test
    fun `updateIfPresent does not create a missing key`() {
        val cache = CacheManager()

        val applied = cache.updateIfPresentAs<List<String>>("queues") { rows -> rows + "a" }

        assertFalse(applied)
        assertNull(cache.get("queues"), "key 不存在时什么都不该写")
        assertEquals(0, cache.size(), "不该留下空壳条目")
    }

    @Test
    fun `updateIfPresent applies the transform when the key exists`() {
        val cache = CacheManager()
        cache.put("queues", listOf("a"))

        val applied = cache.updateIfPresentAs<List<String>>("queues") { rows -> rows + "b" }

        assertTrue(applied)
        assertEquals(listOf("a", "b"), cache.getAs<List<String>>("queues"))
    }

    @Test
    fun `updateAs treats a value of another type as absent`() {
        val cache = CacheManager()
        cache.put("k", 42)
        var received: Any? = "sentinel"

        cache.updateAs<List<String>>("k") { current ->
            received = current
            current
        }

        assertNull(received, "存的类型不符时应该按「不存在」处理")
        assertNull(cache.get("k"), "transform 收到 null 并原样返回 null = 删除这一条")
    }

    /**
     * P0-1 的回归用例：4 个线程各自对同一个 key 做 100 次「追加一个元素」。
     *
     * 旧写法（`getAs` 读整表 → 改 → `put` 写回）在这里会丢条目：后写的那次把先写的
     * 整段抹掉。`update` 把它收进一次 CAS，结果必须恰好 400 条且无重复。
     */
    @Test
    fun `concurrent updates do not lose entries`() {
        val cache = CacheManager()
        cache.put("list", emptyList<Int>())
        val threads = 4
        val perThread = 100
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val pool = Executors.newFixedThreadPool(threads)

        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i ->
                    cache.updateAs<List<Int>>("list") { current ->
                        (current ?: emptyList()) + (t * perThread + i)
                    }
                }
                done.countDown()
            }
        }
        start.countDown()

        assertTrue(done.await(20, TimeUnit.SECONDS), "并发 update 超时")
        pool.shutdown()
        val rows = cache.getAs<List<Int>>("list").orEmpty()
        assertEquals(threads * perThread, rows.size, "并发的读-改-写不该丢条目")
        assertEquals(threads * perThread, rows.toSet().size, "同一元素不该被重试写入两次以上")
    }
}
