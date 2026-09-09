package com.mqclient.uicommon.i18n

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Behavioural tests for the i18n startup loading contract:
 *
 *  - `config.general.language` == ENGLISH  → English string table;
 *  - missing (null) or anything else (incl. SIMPLIFIED_CHINESE) → Chinese table;
 *  - unknown keys never crash — they come back as the key itself.
 *
 * These run against the real bundled JSON resources (Gradle puts the
 * processed composeResources on the jvmTest classpath), so they also guard
 * the packaging/parse pipeline, not just the merge logic.
 */
class LanguageResourcesTest {

    private fun load(code: String?) = runBlocking { LanguageResources.load(code) }

    @Test
    fun `english loads the english table`() {
        load(LanguageResources.LANGUAGE_EN_US)
        assertEquals(LanguageResources.LANGUAGE_EN_US, LanguageResources.currentLanguage())
        assertEquals("Settings", LanguageResources.stringResource("settings_title"))
        assertEquals("File", LanguageResources.stringResource("menu_file"))
        assertEquals("New Connection", LanguageResources.stringResource("menu_new_connection"))
        assertEquals("Save", LanguageResources.stringResource("common_save"))
    }

    @Test
    fun `missing language defaults to chinese`() {
        load(null)
        assertEquals(LanguageResources.LANGUAGE_ZH_CN, LanguageResources.currentLanguage())
        assertEquals("设置", LanguageResources.stringResource("settings_title"))
        assertEquals("文件", LanguageResources.stringResource("menu_file"))
        assertEquals("新建连接", LanguageResources.stringResource("menu_new_connection"))
        assertEquals("保存", LanguageResources.stringResource("common_save"))
    }

    @Test
    fun `chinese language code loads chinese`() {
        load(LanguageResources.LANGUAGE_ZH_CN)
        assertEquals(LanguageResources.LANGUAGE_ZH_CN, LanguageResources.currentLanguage())
        assertEquals("设置", LanguageResources.stringResource("settings_title"))
    }

    @Test
    fun `unknown language code falls back to chinese`() {
        // Any value that isn't exactly en-US (a future third language, a
        // stale enum name, junk) must not crash and must render Chinese.
        load("fr-FR")
        assertEquals(LanguageResources.LANGUAGE_ZH_CN, LanguageResources.currentLanguage())
        assertEquals("设置", LanguageResources.stringResource("settings_title"))
    }

    @Test
    fun `unknown key returns the key itself`() {
        load(LanguageResources.LANGUAGE_EN_US)
        assertEquals("some_missing_key", LanguageResources.stringResource("some_missing_key"))
    }

    @Test
    fun `english and chinese tables actually differ`() {
        load(LanguageResources.LANGUAGE_EN_US)
        val en = LanguageResources.stringResource("menu_file")
        load(LanguageResources.LANGUAGE_ZH_CN)
        val zh = LanguageResources.stringResource("menu_file")
        assertNotEquals(en, zh)
        assertEquals("File", en)
        assertEquals("文件", zh)
    }
}
