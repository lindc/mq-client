package com.mqclient.uicommon.table

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 属性 / 数据表格的**唯一视觉标尺**。
 *
 * 数值来源：`ui-common` 的 `PropertyTable`（IBM MQ / Kafka / RabbitMQ / RocketMQ 属性
 * 对话框都用它），以及沿用它规范的三张自绘列表（Kafka 分区表、RocketMQ 路由表、
 * RabbitMQ 绑定表）。这些数字此前以字面量形式散落在六七个文件里，每加一张表就
 * 抄一遍 —— 抄错一处，同一窗口里两张表就不再是一家。集中到这里后：
 *
 *  - [PropertyTable] 与 [DataGrid] 读同一组常量，改一处两边同时生效；
 *  - 需要新表的模块也只写「列怎么分」，不再重复行高 / 斑马纹 / 圆角 / 行距。
 *
 * 不在本文件里的两件事：**列宽**（每张表业务不同，由调用方的 `weight` 决定）与
 * **表头**（Key-Value 表没有表头；多列表的表头样式见 [DataGrid]）。
 */
internal object TableVisuals {

    /** 数据行最小高度。40dp = 一行 bodyMedium + 上下各 8dp 内边距。 */
    val RowMinHeight = 40.dp

    /** 行内水平内边距。 */
    val RowHorizontalPadding = 10.dp

    /** 行内垂直内边距。 */
    val RowVerticalPadding = 8.dp

    /**
     * 斑马纹底色透明度：底色取 `surfaceVariant`，压到 30%。
     * 再深会抢文字，再浅则隔着屏幕分不出行。
     */
    const val ZebraAlpha = 0.3f

    /**
     * 行圆角。斑马纹行靠圆角 + 行距从背景里"浮"起来，不是靠描边 ——
     * 描边会和多列表的列分隔线打架。
     */
    val RowShape = RoundedCornerShape(4.dp)

    /** 行距。相邻两行的斑马纹之间留出的背景缝，也是圆角成立的前提。 */
    val RowSpacing = 8.dp

    /**
     * 单元格右侧留白。加在列宽分配**之后**（槽位内部），所以不改变表头与行的列宽，
     * 只保证截断的文字不会贴到下一列 —— 否则 `CURRENT_TIMESTAMP` 被裁掉后看起来
     * 像和后面的 `-` 连成了一个值。
     */
    val CellGap = 8.dp
}
