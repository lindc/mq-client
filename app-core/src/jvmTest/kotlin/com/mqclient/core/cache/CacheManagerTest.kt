package com.mqclient.core.cache

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CacheManager 的行为契约测试：
 *  - put / get / getAs 的读写与类型转换（类型不符返回 null，不抛异常）；
 *  - TTL 过期 + 惰性删除（读取时顺手清掉，不需要扫描线程）；
 *  - ttlMs = 0 永不过期；
 *  - remove / removeByPrefix（字面前缀，点边界不误伤）/ clear；
 *  - lastUpdatedAt 与 isFresh 的新鲜度语义（从没写过 = 不新鲜）；
 *  - 空白 key / 前缀被拒绝；
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
}
