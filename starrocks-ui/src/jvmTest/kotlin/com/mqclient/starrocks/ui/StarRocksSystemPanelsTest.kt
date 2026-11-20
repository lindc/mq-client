@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.uicommon.i18n.LanguageResources
import com.mqclient.uicommon.i18n.stringResource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 四个系统页（backends / ha / variables / logs）的**组合冒烟测试**。
 *
 * 断言三件事：
 *  1. 面板能在 Compose 画布上组合出来（布局修饰符不合法、`weight` 用错作用域这类
 *     问题编译期发现不了，只有真正组合一次才暴露）；
 *  2. 标题文案来自 i18n 表（不能给用户看裸 key）；
 *  3. 连接到不存在的连接时走**错误态**而不是崩溃 —— 四个面板的取数都在
 *     `LaunchedEffect` 里，这里正是它最容易出错的地方。
 *
 * 不触网：连接 id 是伪造的，`StarRocksModule` 在测试 JVM 里未初始化，
 * 取数会立刻以异常结束并落在错误分支。
 */
class StarRocksSystemPanelsTest {

    private fun loadTables(language: String) = runBlocking {
        LanguageResources.load(language, emptyList())
    }

    private fun assertPanelRenders(titleKey: String, panel: @androidx.compose.runtime.Composable () -> Unit) {
        loadTables(LanguageResources.LANGUAGE_ZH_CN)
        val resolved = stringResource(titleKey)
        assertTrue(resolved != titleKey, "文案键必须能在语言表中解析：$titleKey")
        runComposeUiTest {
            setContent { MaterialTheme { panel() } }
            onNodeWithText(resolved).assertExists()
        }
    }

    @Test
    fun `backends panel composes`() = assertPanelRenders("olap.starrocks.backends.title") {
        StarRocksBackendsPanel(connectionId = "test-connection", modifier = Modifier.fillMaxSize())
    }

    @Test
    fun `ha panel composes`() = assertPanelRenders("olap.starrocks.ha.title") {
        StarRocksHaPanel(connectionId = "test-connection", modifier = Modifier.fillMaxSize())
    }

    @Test
    fun `variables panel composes`() = assertPanelRenders("olap.starrocks.variables.title") {
        StarRocksVariablesPanel(connectionId = "test-connection", modifier = Modifier.fillMaxSize())
    }

    @Test
    fun `logs panel composes`() = assertPanelRenders("olap.starrocks.logs.title") {
        StarRocksLogsPanel(connectionId = "test-connection", modifier = Modifier.fillMaxSize())
    }

    @Test
    fun `every system menu key resolves in both languages`() = runBlocking {
        listOf(
            "olap.starrocks.menu.backends",
            "olap.starrocks.menu.ha",
            "olap.starrocks.menu.variables",
            "olap.starrocks.menu.logs",
        ).forEach { key ->
            LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, emptyList())
            assertTrue(stringResource(key) != key, "zh-CN 缺键：$key")
            LanguageResources.load(LanguageResources.LANGUAGE_EN_US, emptyList())
            assertTrue(stringResource(key) != key, "en-US 缺键：$key")
        }
    }
}
