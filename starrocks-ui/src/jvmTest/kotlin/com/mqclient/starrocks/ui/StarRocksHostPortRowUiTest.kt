@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.starrocks.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.uicommon.i18n.LanguageResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * 主机 / 端口并排布局的**回归守卫**。
 *
 * 现场：两个字段都套了 ui-common `LabeledField`（根 `Column` 硬编码
 * `fillMaxWidth()`），在 Row 里互相抢整行宽度 —— 主机输入框消失、端口标签
 * 被挤到边缘竖排折行。修复后按列分配宽度（weight / 固定 120dp）。
 *
 * 这里不查源码引用，而是查**渲染结果**：两个输入框的当前值
 * （"localhost" / "9030"）都必须 `assertIsDisplayed` —— 有可见区域。
 * 布局再次崩塌时这两条会直接红。
 */
class StarRocksHostPortRowUiTest {

    @AfterTest
    fun tearDown() {
        runBlocking { LanguageResources.load(null) }
    }

    private fun loadTables() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, emptyList())
    }

    @Test
    fun `host and port inputs are both displayed side by side`() = runComposeUiTest {
        loadTables()
        val state = StarRocksConnectionFormState(
            defaultName = "连接 1",
            scope = CoroutineScope(SupervisorJob()),
        )
        setContent {
            MaterialTheme {
                StarRocksConnectionFormContent(state)
            }
        }

        // 两个输入框的当前值都实际可见（不是被挤压成 0 宽 / 折行）。
        onNodeWithText("localhost").assertIsDisplayed()
        onNodeWithText("9030").assertIsDisplayed()
        // 标签同样可见，排除「只剩输入框、标签被挤没」的变体。
        onNodeWithText("主机").assertIsDisplayed()
        onNodeWithText("端口").assertIsDisplayed()
    }
}
