@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 长值可读的交互守卫。
 *
 * 单元格固定 160dp + 单行省略，省略号后的内容在 Compose 里**没有布局**
 * （拖选都选不到）。所以必须有一条"能看到完整值"的路径：
 *  - 悬停 tooltip（扫一眼）；
 *  - 点击 → 详情弹窗（完整值 + 复制）。
 *
 * 这里断言第二条——它是唯一能拿到全文的方式，接错了就等于长值依然不可读。
 */
class StarRocksCellDetailTest {

    private val longJson =
        """{"order_id":"A-2026-0001","customer":"acme","items":[{"sku":"X1","qty":3}]}"""

    @Test
    fun `clicking a value cell opens the detail with column name and row index`() = runComposeUiTest {
        var captured: CellDetail? = null
        setContent {
            MaterialTheme {
                ValueCell(
                    value = longJson,
                    columnName = "payload",
                    rowIndex = 6,
                    onOpenDetail = { captured = it },
                )
            }
        }

        onNodeWithText(longJson, substring = true).performMouseInput { moveTo(center); press(); release() }
        waitForIdle()

        assertEquals("payload", captured?.columnName, "详情应带列名（弹窗标题需要）")
        assertEquals(6, captured?.rowIndex, "详情应带行号")
        assertEquals(longJson, captured?.value?.toString(), "完整值必须原样传出去，不能是截断后的")
    }

    @Test
    fun `null cells are not clickable`() = runComposeUiTest {
        var captured: CellDetail? = null
        setContent {
            MaterialTheme {
                ValueCell(value = null, columnName = "remark", rowIndex = 0, onOpenDetail = { captured = it })
            }
        }

        // NULL 渲染为占位文本，点它没有意义（没有可看的内容）。
        onNodeWithText("NULL").performMouseInput { moveTo(center); press(); release() }
        waitForIdle()
        assertNull(captured, "NULL 单元格不应打开详情")
    }

    @Test
    fun `long value is laid out in the fixed cell without breaking layout`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ValueCell(
                    value = "x".repeat(5000),
                    columnName = "big",
                    rowIndex = 0,
                    onOpenDetail = {},
                )
            }
        }
        // 行高恒定是表格对齐的前提：超长值不能让单元格撑开（maxLines=1 + 省略）。
        // 这里只要能组合出来且不抛异常即通过；具体高度由 DataGrid 的行高测试覆盖。
        onNodeWithText("x", substring = true).assertExists()
    }
}
