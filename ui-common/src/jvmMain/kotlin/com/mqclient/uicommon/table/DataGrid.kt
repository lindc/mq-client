package com.mqclient.uicommon.table

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 轻量二维数据表格：表头（sticky，不随行滚动）+ `LazyColumn` 行。
 *
 * 为什么不复用 [PropertyTable]：那是 Key-Value 两列属性表（斑马纹 + 复制按钮 +
 * 限宽居中），套不进 7 列的字段清单；也不新造一个「字段表」专用组件 —— 这里抽成
 * 泛型，是因为 SQL 结果集 / 其它元数据清单迟早要同一套列对齐语义。
 *
 * 关键约定：
 *  - **表头与表体共用同一份列约束**：[DataGridColumn] 用 [weight] / [width] 描述列宽，
 *    组件内部同时用于表头单元格与行单元格 —— 调用方只写一次，两边不可能漂移；
 *  - **表头不是 LazyColumn 的一项**：它固定在外层 `Column` 里，滚动永远只滚行；
 *  - **行用 LazyColumn**：只组合视口内的行，字段上千也不掉帧；[rowKey] 给出稳定 key，
 *    重排（例如 DDL 引起的列顺序变化）时复用行状态；
 *  - **hover 高亮**：每行独立 `MutableInteractionSource`，背景走
 *    `surfaceContainerHigh`（与表头同色，和消息表格的 hover 语义一致）；
 *  - 颜色全部取自 `MaterialTheme.colorScheme`，主题切换无需改调用方。
 *
 * 行高固定为「单行文本 + 6dp 上下内边距」的紧凑密度：字段多时要的是横向对比，
 * 不是每个字段一块卡片。
 */
data class DataGridColumn<T>(
    /** 表头文案（调用方负责 i18n 解析）。 */
    val title: String,
    /** 行内分配权重；与 [width] 互斥，给了 [width] 就以定宽为准。 */
    val weight: Float = 1f,
    /** 定宽列（数字 / 状态位这类不需要吃剩余宽度的列）。 */
    val width: Dp? = null,
    /** 单元格渲染。只应放单行文本类内容，多行会破坏行高一致。 */
    val cell: @Composable (T) -> Unit,
)

private val GridHorizontalPadding = 10.dp
private val GridVerticalPadding = 6.dp

/**
 * 渲染表格。调用方负责空态（空 [rows] 只画表头）与加载态 —— 组件不做业务判断。
 *
 * [modifier] 需要给出有界高度（典型是父 `ColumnScope` 里的 `Modifier.weight(1f)`）：
 * 行列表在内部吃掉剩余高度并自行滚动。
 */
@Composable
fun <T> DataGrid(
    columns: List<DataGridColumn<T>>,
    rows: List<T>,
    modifier: Modifier = Modifier,
    rowKey: ((T) -> Any)? = null,
) {
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = GridHorizontalPadding, vertical = GridVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEach { column -> TableHeaderText(column.title, slotModifier(column)) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(rows, key = rowKey) { row -> DataGridRow(columns, row) }
        }
    }
}

/** 表头 / 单元格共用的宽度约束：`weight` 只在 Row 内成立，所以收进 RowScope 扩展。 */
private fun <T> RowScope.slotModifier(column: DataGridColumn<T>): Modifier {
    val fixed = column.width
    return if (fixed != null) Modifier.width(fixed) else Modifier.weight(column.weight)
}

/** 一行：hover 高亮 + 与表头逐列对齐。 */
@Composable
private fun <T> DataGridRow(columns: List<DataGridColumn<T>>, row: T) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource)
            .background(
                if (hovered) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Transparent
                },
            )
            .padding(horizontal = GridHorizontalPadding, vertical = GridVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            // 包一层 Box：把 weight / width 交给容器，单元格自身不参与宽度分配。
            Box(slotModifier(column)) { column.cell(row) }
        }
    }
}

/**
 * 单元格默认文案：单行 + 省略号（行高恒定，列对齐不会被长类型名撑歪）。
 *
 * [monospace] 用于标识符 / 默认值这类「逐字符比对」的内容；[subdued] 用于占位的 `-`。
 */
@Composable
fun DataGridTextCell(
    text: String,
    monospace: Boolean = false,
    emphasized: Boolean = false,
    subdued: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = if (monospace) FontFamily.Monospace else null,
        fontWeight = if (emphasized) FontWeight.Medium else null,
        color = if (subdued) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
