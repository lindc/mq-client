@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.starrocks.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.uicommon.i18n.LanguageResources
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [StarRocksAdapterUI] 的**冒烟测试**：占位页能在 Compose 画布上渲染、文案确实来自
 * i18n 表（键必须解析成功，不能把裸 key 显示给用户），且 SPI 契约（serviceId /
 * title / i18nTables / contentFor）与宿主约定一致。
 *
 * 参照仓库既有画布测试（KafkaMessageBrowseToolbarTest / DetailCompositionCostTest）
 * 与 ui-test-kit 的 ComposeUiTest 透传；不依赖真实数据库、不触网。
 */
class StarRocksAdapterUITest {

    private fun loadTables(language: String) = runBlocking {
        // olap.starrocks.* 文案现在由**模块私有表**提供（不再挤在 ui-common 公共表里），
        // 所以测试必须像宿主一样把 i18nTables 传进去。
        LanguageResources.load(language, StarRocksAdapterUI().i18nTables)
    }

    @Test
    fun `placeholder renders localized text in zh-CN`() = runComposeUiTest {
        loadTables(LanguageResources.LANGUAGE_ZH_CN)
        val resolved = stringResource("olap.starrocks.placeholder_title")
        assertTrue(resolved != "olap.starrocks.placeholder_title", "文案键必须能在语言表中解析")
        setContent { MaterialTheme { StarRocksPlaceholder() } }
        onNodeWithText(resolved).assertExists()
    }

    @Test
    fun `placeholder renders localized text in en-US`() = runComposeUiTest {
        loadTables(LanguageResources.LANGUAGE_EN_US)
        val resolved = stringResource("olap.starrocks.placeholder_title")
        assertTrue(resolved != "olap.starrocks.placeholder_title")
        setContent { MaterialTheme { StarRocksPlaceholder() } }
        onNodeWithText(resolved).assertExists()
    }

    @Test
    fun `adapter ui exposes its contract`() {
        val ui = StarRocksAdapterUI()
        assertEquals("starrocks", ui.serviceId)
        assertEquals("StarRocks", ui.title)
        assertNotNull(ui.contentFor("catalog", "any-connection"))
        // 模块私有 i18n 表必须声明，否则 olap.starrocks.* 全部解析成裸 key。
        assertEquals(1, ui.i18nTables.size, "应声明一张模块私有表")
        assertEquals("starrocks_ui", ui.i18nTables.single().base)
    }
}
