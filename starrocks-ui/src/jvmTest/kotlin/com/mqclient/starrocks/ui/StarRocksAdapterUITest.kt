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
 * UI 侧空骨架的**冒烟测试**：占位页能在 Compose 画布上渲染，且文案确实来自
 * i18n 表（键必须解析成功，不能把裸 key 显示给用户）。
 *
 * 参照仓库既有画布测试（KafkaMessageBrowseToolbarTest / DetailCompositionCostTest）
 * 与 ui-test-kit 的 ComposeUiTest 透传；不依赖真实数据库、不触网。
 */
class StarRocksAdapterUITest {

    private fun loadTables(language: String) = runBlocking {
        // 全局语言表（ui-common）已含 olap.starrocks.* 键；模块私有表为空。
        LanguageResources.load(language, emptyList())
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
    fun `adapter ui exposes placeholder contract without data binding`() {
        val ui = StarRocksAdapterUI()
        assertEquals("starrocks", ui.serviceId)
        assertEquals("StarRocks", ui.title)
        assertNotNull(ui.contentFor("catalog", "any-connection"))
        assertTrue(ui.i18nTables.isEmpty(), "空骨架用全局语言表，不声明模块私有表")
    }
}
