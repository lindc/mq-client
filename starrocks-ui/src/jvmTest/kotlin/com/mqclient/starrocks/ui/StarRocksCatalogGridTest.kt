@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.mqclient.uicommon.table.DataGrid
import com.mqclient.uicommon.table.DataGridColumn
import com.mqclient.uicommon.table.DataGridTextCell
import kotlin.test.Test

/**
 * 字段二维表格的冒烟测试（`ui-common` 的 [DataGrid]）。
 *
 * 为什么在这里测而不是 ui-common：Compose 画布测试依赖（ui-test-kit +
 * `compose.desktop.currentOs`）已经在 starrocks-ui 的 jvmTest 里声明，
 * 不为一个用例给共享模块加整套测试基建。
 *
 * 断言的是「表头 + 每行一格」这条最小契约：列宽对齐由表头 / 表体共用同一份
 * [DataGridColumn] 约束保证（组件内部实现），画布测试也只能验证内容确实渲染出来。
 */
class StarRocksCatalogGridTest {

    private data class GridRow(val name: String, val type: String)

    private fun columns(): List<DataGridColumn<GridRow>> = listOf(
        DataGridColumn<GridRow>(title = "Name", weight = 0.6f) { row ->
            DataGridTextCell(row.name, monospace = true, emphasized = true)
        },
        DataGridColumn<GridRow>(title = "Type", weight = 0.4f) { row ->
            DataGridTextCell(row.type)
        },
    )

    @Test
    fun `grid renders header and one row per entry`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DataGrid(
                    columns = columns(),
                    rows = listOf(
                        GridRow("id", "bigint"),
                        GridRow("customer_name", "varchar(64)"),
                    ),
                    rowKey = { it.name },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        onNodeWithText("Name").assertExists()
        onNodeWithText("Type").assertExists()
        onNodeWithText("id").assertExists()
        onNodeWithText("customer_name").assertExists()
        onNodeWithText("varchar(64)").assertExists()
    }

    @Test
    fun `empty grid still renders the header`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DataGrid(
                    columns = columns(),
                    rows = emptyList(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 空表只画表头：调用方负责空态文案，表格本身不吞掉列定义。
        onNodeWithText("Name").assertExists()
        onNodeWithText("Type").assertExists()
    }
}
