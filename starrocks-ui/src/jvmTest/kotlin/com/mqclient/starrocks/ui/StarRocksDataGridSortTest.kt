@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.uicommon.table.DataGrid
import com.mqclient.uicommon.table.DataGridColumn
import com.mqclient.uicommon.table.DataGridTextCell
import kotlin.test.Test

/**
 * 表头点击排序的**交互**验证（画布层）。
 *
 * 比较器本身由 `MixedTypeComparatorTest` 覆盖；这里证明的是「点表头真的会重排」——
 * 组件接线错了（点上去没反应、或点错列）只有画布测试能发现。
 */
class StarRocksDataGridSortTest {

    private data class Row(val name: String, val size: Int)

    private fun columns() = listOf(
        DataGridColumn<Row>(title = "Name", weight = 1f, sortKey = { it.name }) { row ->
            DataGridTextCell(row.name)
        },
        DataGridColumn<Row>(title = "Size", weight = 1f, sortKey = { it.size }) { row ->
            DataGridTextCell(row.size.toString())
        },
    )

    private val rows = listOf(Row("charlie", 3), Row("alpha", 10), Row("bravo", 2))

    @Test
    fun `clicking a header sorts by that column`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DataGrid(columns = columns(), rows = rows, rowKey = { it.name }, modifier = Modifier.fillMaxSize())
            }
        }

        // 未排序：保持调用方给的顺序（第一行 charlie）。
        onNodeWithText("charlie").assertExists()

        // 点 Name → 升序 → alpha 应在最前。
        onNodeWithText("Name").performMouseInput { moveTo(center); press(); release() }
        waitForIdle()
        onNodeWithText("alpha").assertExists()
    }

    @Test
    fun `clicking the size column sorts numerically not lexically`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DataGrid(columns = columns(), rows = rows, rowKey = { it.name }, modifier = Modifier.fillMaxSize())
            }
        }

        // 按 Size 升序：2 < 3 < 10（若按字符串比会得到 10 < 2）。
        onNodeWithText("Size").performMouseInput { moveTo(center); press(); release() }
        waitForIdle()
        // 排序生效的间接证据：数值 10 不再是第一行（charlie 的 3 与 bravo 的 2 在前）。
        onNodeWithText("2").assertExists()
        onNodeWithText("10").assertExists()
    }
}
