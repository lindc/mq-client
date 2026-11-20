package com.mqclient.uicommon.table

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 表头语义标签（渲染测试断言行高用：表头与数据行必须同高）。 */
const val DataGridHeaderTag = "datagrid_header"

/** 斑马纹行语义标签。 */
const val DataGridZebraRowTag = "datagrid_zebra_row"

/** 普通行语义标签。 */
const val DataGridPlainRowTag = "datagrid_plain_row"

/**
 * 多列数据表：sticky 表头 + `LazyColumn` 行。
 *
 * 与 `PropertyTable`（Key-Value 两列属性表）的关系：**同一个视觉家族，不同的数据形状**。
 * 行高 / 内边距 / 斑马纹透明度 / 圆角 / 行距全部读 [TableVisuals] —— 也就是 IBM MQ /
 * Kafka / RabbitMQ / RocketMQ 属性对话框那套标尺。因此本表与 Kafka 分区表、
 * RocketMQ 路由表、RabbitMQ 绑定表在同一个窗口里并排也不会「不像一家」。
 *
 * 为什么不是把 7 列塞进 `PropertyTable`：那张表一行只有一个 Key 和一个 Value，
 * 表达 7 个属性只能堆成 7 个分组（一个字段一块），正是数据库客户端用户要避免的形态。
 *
 * 关键约定：
 *  - **表头与表体共用同一份列约束**：[DataGridColumn] 用 [weight] / [width] 描述列宽，
 *    组件内部同时用于表头单元格与行单元格 —— 调用方只写一次，两边不可能漂移；
 *  - **表头不是 LazyColumn 的一项**：固定在 [Column] 顶部，滚动永远只滚行；
 *  - **斑马纹**默认开启（[zebraStriping]），与 [TableVisuals.ZebraAlpha] 同源；
 *  - **hover 高亮**：每行独立 `MutableInteractionSource`，`surfaceContainerHigh` 压过斑马纹；
 *  - **行用 LazyColumn**：只组合视口内的行，字段上千也不掉帧；[rowKey] 给出稳定 key。
 */
data class DataGridColumn<T>(
    /** 表头文案（调用方负责 i18n 解析）。 */
    val title: String,
    /** 列宽比例。整数比例即可（2f / 1.5f / 1f），不必归一到 1；与 [width] 互斥。 */
    val weight: Float = 1f,
    /** 定宽列（数字 / 状态位这类不需要吃剩余宽度的列）。 */
    val width: Dp? = null,
    /** 单元格渲染。只应放单行文本类内容，多行会破坏行高一致。 */
    val cell: @Composable (T) -> Unit,
)

/**
 * 渲染表格。调用方负责空态（空 [rows] 只画表头）与加载态 —— 组件不做业务判断。
 *
 * [modifier] 需要给出有界高度（典型是父 `ColumnScope` 里的 `Modifier.weight(1f)`）：
 * 行列表在内部吃掉剩余高度并自行滚动。
 *
 * [zebraStriping] 默认开启。关掉只在「行本身已经带状态色」这类场景才有意义
 * （例如整行按严重级别染色的告警表）。
 */
@Composable
fun <T> DataGrid(
    columns: List<DataGridColumn<T>>,
    rows: List<T>,
    modifier: Modifier = Modifier,
    rowKey: ((T) -> Any)? = null,
    zebraStriping: Boolean = true,
    onRowClick: ((T) -> Unit)? = null,
) {
    Column(modifier) {
        // 表头：与数据行同一档字号（bodyMedium），靠背景 + 字重 + 主色区分 ——
        // 不放大字号，否则表头会显得像标题栏而不是行首。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DataGridHeaderTag)
                .heightIn(min = TableVisuals.RowMinHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(
                    horizontal = TableVisuals.RowHorizontalPadding,
                    vertical = TableVisuals.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEach { column ->
                TableHeaderText(
                    text = column.title,
                    modifier = slotModifier(column),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            // 行距与 PropertyTable 的 spacedBy(RowSpacing) 相同：斑马纹行靠间隙 + 圆角浮起。
            verticalArrangement = Arrangement.spacedBy(TableVisuals.RowSpacing),
        ) {
            itemsIndexed(
                items = rows,
                key = if (rowKey == null) null else { _, row -> rowKey(row) },
            ) { index, row ->
                DataGridRow(
                    columns = columns,
                    row = row,
                    striped = zebraStriping && index % 2 == 1,
                    onClick = onRowClick,
                )
            }
        }
    }
}

/** 表头 / 单元格共用的宽度约束：`weight` 只在 Row 内成立，所以收进 RowScope 扩展。 */
private fun <T> RowScope.slotModifier(column: DataGridColumn<T>): Modifier {
    val fixed = column.width
    val slot = if (fixed != null) Modifier.width(fixed) else Modifier.weight(column.weight)
    return slot.padding(end = TableVisuals.CellGap)
}

/** 一行：hover 高亮 / 斑马纹 + 与表头逐列对齐（行高、圆角、内边距全部同 [TableVisuals]）。 */
@Composable
private fun <T> DataGridRow(
    columns: List<DataGridColumn<T>>,
    row: T,
    striped: Boolean,
    onClick: ((T) -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val background = when {
        // hover 压过斑马纹：悬浮行永远清晰，奇数行不会因为底色相近而看不出反馈。
        hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
        striped -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = TableVisuals.ZebraAlpha)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 测试钩子在 heightIn 之前：语义节点的 bounds 是「此处之后全部修饰符」占据的
            // 区域，放在 padding 之后会报出内容区高度，行高断言就看不到真实行高
            // （与 Kafka 分区表 / RocketMQ 路由表的写法一致）。
            .testTag(if (striped) DataGridZebraRowTag else DataGridPlainRowTag)
            .heightIn(min = TableVisuals.RowMinHeight)
            .clip(TableVisuals.RowShape)
            .then(if (onClick == null) Modifier else Modifier.clickable { onClick(row) })
            .hoverable(interactionSource)
            .background(background)
            .padding(
                horizontal = TableVisuals.RowHorizontalPadding,
                vertical = TableVisuals.RowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { column ->
            // 包一层 Box：把 weight / width 交给容器，单元格自身不参与宽度分配。
            Box(slotModifier(column)) { column.cell(row) }
        }
    }
}

/**
 * 单元格默认文案：bodyMedium（与 `PropertyTable` 的 Value 列同档）+ 单行省略。
 *
 * [emphasized] 用于行首标识符这类需要一眼定位的列（Medium 字重）；
 * [subdued] 用于占位的 `-` 与次要信息（`onSurfaceVariant`）。
 */
@Composable
fun DataGridTextCell(
    text: String,
    emphasized: Boolean = false,
    subdued: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
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
