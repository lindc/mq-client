package com.mqclient.olap.base.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 错误码 i18n 键的形状守卫。
 *
 * 背景：`OlapCategory.I18N_KEY_PREFIX` 的值是 `"olap."`（带尾点）。原实现每个枚举项
 * 自己拼 `"$I18N_KEY_PREFIX.err.xxx"`，得到 `olap..err.xxx`（**双点**）—— 语言表里
 * 永远查不到，所有 OLAP 错误静默退回英文兜底，且没有任何编译期或测试期提示。
 *
 * 这些用例锁住三件事：
 *  1. 键形状正确（无空段、`olap.err.` 前缀）；
 *  2. 兜底文案是可直接展示的英文（service 层不产出中文）；
 *  3. 键唯一（两个枚举项共用一个键会让根因判断失真）。
 */
class OlapErrorCodeTest {

    private val entries = OlapErrorCode.entries

    @Test
    fun `every error key is a well formed olap err key`() {
        entries.forEach { code ->
            val key = code.errorKey
            assertTrue(
                key.startsWith("olap.err."),
                "${code.name} 的键应以 olap.err. 开头，实际 $key",
            )
            assertFalse(key.contains(".."), "${code.name} 的键含空段（双点）：$key")
            assertFalse(key.endsWith("."), "${code.name} 的键以点结尾：$key")
        }
    }

    @Test
    fun `error keys are unique`() {
        val keys = entries.map { it.errorKey }
        assertEquals(
            keys.size,
            keys.toSet().size,
            "存在重复键：${keys.groupBy { it }.filter { it.value.size > 1 }}",
        )
    }

    @Test
    fun `fallback messages are non blank english`() {
        entries.forEach { code ->
            assertTrue(code.fallbackMessage.isNotBlank(), "${code.name} 缺英文兜底文案")
            assertFalse(
                code.fallbackMessage.any { it.code in 0x4E00..0x9FFF },
                "${code.name} 的兜底文案含中文：${code.fallbackMessage}",
            )
        }
    }

    @Test
    fun `object not found is distinguishable from syntax error`() {
        // 拼错表名与写错语法是两种修法；合并成一类等于让用户猜。
        assertTrue(
            OlapErrorCode.OBJECT_NOT_FOUND != OlapErrorCode.SYNTAX_ERROR,
            "OBJECT_NOT_FOUND 与 SYNTAX_ERROR 必须是两个分类",
        )
        assertEquals("olap.err.object_not_found", OlapErrorCode.OBJECT_NOT_FOUND.errorKey)
    }
}
