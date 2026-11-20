package com.mqclient.starrocks.ui

import com.mqclient.olap.base.error.OlapErrorCode
import com.mqclient.uicommon.i18n.LanguageResources
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * OLAP 错误文案必须真的能解析（不然 `userMessage()` 静默退回英文）。
 *
 * 这个问题曾被两层掩盖：
 *  1. 键是 `olap..err.xxx`（双点）—— 拼错，永远查不到；
 *  2. 语言表里根本没有任何 `olap.err.*` 条目。
 * 两者叠加的结果是：所有 OLAP 错误对中文用户都显示英文，而代码看起来「有 i18n」。
 *
 * `OlapErrorCodeTest` 守键的形状，这里守「表里确实有条目且能解析」——
 * 只有两边都成立，翻译才真正生效。
 */
class OlapErrorLocalizationTest {

    @Test
    fun `every olap error key resolves in both languages`() {
        listOf(LanguageResources.LANGUAGE_ZH_CN, LanguageResources.LANGUAGE_EN_US).forEach { lang ->
            runBlocking { LanguageResources.load(lang, StarRocksAdapterUI().i18nTables) }
            OlapErrorCode.entries.forEach { code ->
                val resolved = stringResource(code.errorKey)
                assertTrue(
                    resolved != code.errorKey,
                    "$lang 缺键 ${code.errorKey}（会回落成裸 key）",
                )
                assertTrue(resolved.isNotBlank(), "$lang 的 ${code.errorKey} 是空串")
            }
        }
    }

    @Test
    fun `chinese translations are actually chinese`() {
        runBlocking { LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, StarRocksAdapterUI().i18nTables) }
        OlapErrorCode.entries.forEach { code ->
            val resolved = stringResource(code.errorKey)
            assertTrue(
                resolved.any { it.code in 0x4E00..0x9FFF },
                "${code.errorKey} 的中文条目不含汉字，可能漏译：$resolved",
            )
        }
    }
}
