@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.rabbitmq.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.mqclient.rabbitmq.RabbitMqBindingSnapshot
import com.mqclient.uicommon.i18n.LanguageResources
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 交换机属性对话框下半部分「绑定关系」表的样式守卫。
 *
 * 现场问题：上半部分是 `PropertyTable`（bodyMedium / 40dp 行高 / 斑马纹），下半部分
 * 的绑定表是手写的 bodySmall + 6dp 内边距 + 0.5dp 分割线（行高约 24dp）—— 同一个
 * 对话框里两块表格差了一个字号档和一个行高档。
 *
 * 这里断言的是**渲染结果**（行高、背景像素），不是源码里写了什么：样式回归不会让
 * 编译失败，只会让界面安静地又变小一号。
 */
class RabbitMqBindingsTableTest {

    private fun loadTables() = runBlocking {
        LanguageResources.load(LanguageResources.LANGUAGE_ZH_CN, RabbitMqUI().i18nTables)
    }

    private fun bindings() = listOf(
        RabbitMqBindingSnapshot(
            source = "orders.x",
            destination = "orders.queue",
            destinationType = "queue",
            routingKey = "orders.created",
        ),
        RabbitMqBindingSnapshot(
            source = "orders.x",
            destination = "orders.dlx",
            destinationType = "exchange",
            routingKey = "#",
        ),
        RabbitMqBindingSnapshot(
            source = "orders.x",
            destination = "orders.audit",
            destinationType = "queue",
            routingKey = "orders.*",
        ),
    )

    private fun inkPixels(map: PixelMap): Int {
        val bg = map[0, 0]
        var ink = 0
        for (y in 0 until map.height) {
            for (x in 0 until map.width) {
                if (map[x, y] != bg) ink++
            }
        }
        return ink
    }

    @Test
    fun `binding rows keep the 40dp row height of the property table above`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme {
                BindingsTable(bindings(), onDelete = {})
            }
        }
        waitForIdle()

        // 表头四列
        onNodeWithText("Destination").assertExists()
        onNodeWithText("Routing Key").assertExists()
        onNodeWithText("Arguments").assertExists()

        // 数据：exchange 类型带 [E] 前缀（原有逻辑不能丢）
        onNodeWithText("orders.queue").assertExists()
        onNodeWithText("[E] orders.dlx").assertExists()
        onNodeWithText("orders.created").assertExists()

        onAllNodesWithTag(BINDING_PLAIN_ROW_TAG)[0].assertHeightIsAtLeast(BINDING_ROW_MIN_HEIGHT)
        onAllNodesWithTag(BINDING_ZEBRA_ROW_TAG)[0].assertHeightIsAtLeast(BINDING_ROW_MIN_HEIGHT)
    }

    /**
     * 斑马纹：奇数行自己画背景（alpha > 0），偶数行透明（alpha == 0，交给父背景）。
     *
     * `captureToImage` 只捕获节点自身的绘制、不含父背景，所以这条正好能分开两种状态。
     */
    @Test
    fun `odd binding rows are striped like the property table`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme {
                BindingsTable(bindings(), onDelete = {})
            }
        }
        waitForIdle()

        fun edgeAlpha(tag: String): Float {
            val map = onAllNodesWithTag(tag)[0].captureToImage().toPixelMap()
            // 右侧边缘：列内容都在左边，这里必定是纯底色。
            return map[map.width - 6, map.height / 2].alpha
        }

        val plain = edgeAlpha(BINDING_PLAIN_ROW_TAG)
        val zebra = edgeAlpha(BINDING_ZEBRA_ROW_TAG)
        assertTrue(zebra > 0f, "斑马行必须自己画背景（实测 alpha=$zebra）")
        assertTrue(plain == 0f, "普通行必须保持透明（实测 alpha=$plain）")
        assertNotEquals(plain, zebra)
    }

    @Test
    fun `rows are painted - no blank band`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme {
                BindingsTable(bindings(), onDelete = {})
            }
        }
        waitForIdle()
        val ink = inkPixels(onAllNodesWithTag(BINDING_PLAIN_ROW_TAG)[0].captureToImage().toPixelMap())
        assertTrue(ink > 0, "数据行必须有内容，实测 ink=$ink")
    }

    @Test
    fun `delete action column disappears when deletion is not allowed`() = runComposeUiTest {
        loadTables()
        setContent {
            MaterialTheme {
                // 系统交换机：onDelete = null → 无操作列（原有行为必须保留）
                BindingsTable(bindings(), onDelete = null)
            }
        }
        waitForIdle()

        onNodeWithText("Destination").assertExists()
        // 操作列的表头不应存在
        onAllNodesWithText("操作").assertCountEqualsZero()
    }

    /** 无删除权限时也能正常布局（不崩、不留空列）。 */
    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() {
        assertTrue(fetchSemanticsNodes().isEmpty(), "不该渲染操作列")
    }
}
