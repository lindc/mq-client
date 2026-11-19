@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.mqclient.starrocks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.table.DataGrid
import com.mqclient.uicommon.table.DataGridColumn
import com.mqclient.uicommon.table.DataGridHeaderTag
import com.mqclient.uicommon.table.DataGridPlainRowTag
import com.mqclient.uicommon.table.DataGridTextCell
import com.mqclient.uicommon.table.DataGridZebraRowTag
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 字段二维表格的画布测试（`ui-common` 的 [DataGrid]）。
 *
 * 为什么在这里测而不是 ui-common：Compose 画布测试依赖（ui-test-kit +
 * `compose.desktop.currentOs`）已经在 starrocks-ui 的 jvmTest 里声明，
 * 不为一个用例给共享模块加整套测试基建。
 *
 * 断言的是一组**像素 / 尺寸约定**，因为语义树里看不到它们：
 *  - 行高 40dp（与 `PropertyTable` 同源，见 `TableVisuals.RowMinHeight`）；
 *  - 斑马纹隔行变色、`zebraStriping = false` 时底色统一；
 *  - hover 改写行底色。
 * 前两条互为反证：把默认值改回「关闭」，斑马纹那条会红。
 */
class StarRocksCatalogGridTest {

    private data class GridRow(val name: String, val type: String)

    private fun columns(): List<DataGridColumn<GridRow>> = listOf(
        DataGridColumn<GridRow>(title = "Name", weight = 0.6f) { row ->
            DataGridTextCell(row.name, emphasized = true)
        },
        DataGridColumn<GridRow>(title = "Type", weight = 0.4f) { row ->
            DataGridTextCell(row.type)
        },
    )

    private val rows = listOf(
        GridRow("id", "bigint"),
        GridRow("customer_name", "varchar(64)"),
        GridRow("amount", "decimal(18,2)"),
        GridRow("created_at", "datetime"),
        GridRow("region", "varchar(16)"),
    )

    /**
     * 沿最右一列把像素按颜色切段。
     *
     * 采样点贴右边（`width - 3`）是刻意的：那里只有行底色，不会被文字抗锯齿污染，
     * 也不依赖字体度量 —— 换字体只会改变段高，不会改变段数。
     */
    private fun columnColorRuns(image: BufferedImage): List<Int> {
        val runs = mutableListOf<Int>()
        for (y in 0 until image.height) {
            val rgb = image.getRGB(image.width - 3, y)
            if (runs.isEmpty() || runs.last() != rgb) runs += rgb
        }
        return runs
    }

    private fun Int.hex() = String.format("#%06X", this and 0xFFFFFF)

    @Test
    fun `grid renders header and one row per entry`() = runComposeUiTest {
        setContent {
            MaterialTheme {
                DataGrid(
                    columns = columns(),
                    rows = rows.take(2),
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

    /**
     * 行高必须与 `PropertyTable` 一致（40dp）—— 这是「和 MQ 属性面板同一张表」的
     * 硬指标，也是本轮返工的原因（旧实现是 6dp 紧凑行，看着像另一套组件）。
     *
     * 用行上的 testTag 定位整行而非文字节点：`onNodeWithText` 的 bounds 是文字
     * 本身的高度（20dp），拿来断言行高永远看不到真实行高。
     */
    @Test
    fun `row height matches the PropertyTable scale`() = runComposeUiTest {
        setContent { GridUnderTest(zebraStriping = true) }

        onAllNodesWithTag(DataGridPlainRowTag)[0].assertHeightIsAtLeast(40.dp)
        onAllNodesWithTag(DataGridZebraRowTag)[0].assertHeightIsAtLeast(40.dp)
        onAllNodesWithTag(DataGridHeaderTag)[0].assertHeightIsAtLeast(40.dp)
    }

    @Test
    fun `zebra striping alternates row backgrounds`() = runComposeUiTest {
        setContent { GridUnderTest(zebraStriping = true) }

        val runs = columnColorRuns(onRoot().captureToImage().toAwtImage())
        // 表头 + 至少两组交替底色 = 5 段起步（5 行数据实测 7 段，中间夹行距）。
        assertTrue(
            runs.size >= 5,
            "斑马纹应产生隔行底色，实际只有 ${runs.size} 段：${runs.map { it.hex() }}",
        )
    }

    @Test
    fun `zebra striping can be turned off`() = runComposeUiTest {
        setContent { GridUnderTest(zebraStriping = false) }

        val runs = columnColorRuns(onRoot().captureToImage().toAwtImage())
        // 关闭后所有行同底色：表头 + 行 = 最多 3 段（含表头与行距的过渡）。
        assertTrue(
            runs.size <= 3,
            "关闭斑马纹后行底色应一致，实际 ${runs.size} 段：${runs.map { it.hex() }}",
        )
    }

    @Test
    fun `hovering a row changes its background`() = runComposeUiTest {
        setContent { GridUnderTest(zebraStriping = false) }

        val before = columnColorRuns(onRoot().captureToImage().toAwtImage())
        onNodeWithText("amount").performMouseInput { moveTo(center) }
        waitForIdle()
        val after = columnColorRuns(onRoot().captureToImage().toAwtImage())

        assertTrue(
            before != after,
            "hover 应改写行底色，前后像素完全一致：${before.map { it.hex() }}",
        )
        // 悬浮行会从周围底色里「站出来」，因此段数只会增不会减；减了说明行高被压掉了。
        assertTrue(
            after.size > before.size,
            "hover 应在行底色上切出新的色段，实际 ${before.size} -> ${after.size}",
        )
    }

    @Composable
    private fun GridUnderTest(zebraStriping: Boolean) {
        // 固定尺寸 + 显式 surface 底：采样列的颜色只受「表头 / 行底色 / 行距」影响。
        Box(Modifier.size(300.dp, 260.dp).background(MaterialTheme.colorScheme.surface)) {
            DataGrid(
                columns = columns(),
                rows = rows,
                rowKey = { it.name },
                zebraStriping = zebraStriping,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
