package com.mqclient.kafka.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.graphics.Color
import com.mqclient.kafka.KafkaGroupSnapshot
import com.mqclient.mqui.objectlist.CardAction
import com.mqclient.mqui.objectlist.ObjectCardData
import com.mqclient.uicommon.i18n.stringResource

/**
 * Group → 卡片数据的适配（与 ibm-mq 的 QueueCardAdapter / ChannelCardAdapter 同一
 * 模式）：
 *
 *  - 行 1：👥 图标 + 组名 + **右上角 Lag**（`metricValue` 位，千分位；超过连接设置
 *    的 [lagWarningThreshold] 换警示橙，0 用次要色，无心跳产物不显示）；
 *  - 行 2 左：**状态色点**（`Icons.Default.Circle`，与 MQ 通道卡片同一实现——
 *    16dp 色点 + 同色文字）+ 状态文字（成员数移到详情对话框，简单组保留文字后缀）；
 *  - 行 2 右：详情 ⓘ 按钮（打开 [KafkaGroupDetailDialog]）。
 */
internal object GroupStatusStyle {
    /** Stable：健康绿（同 MQ 通道 RUNNING 绿）。 */
    val STABLE = Color(0xFF4CAF50)

    /** Empty：中性灰（组没有成员是正常状态，不当作错误——同 MQ 非运行态中性灰）。 */
    val EMPTY = Color(0xFF9E9E9E)

    /** Dead：错误红（同 MQ 告警红）。 */
    val DEAD = Color(0xFFF44336)

    /** Rebalance（Preparing / Completing）：警示橙（项目既有警示色）。 */
    val REBALANCE = Color(0xFFED6C02)

    /** Unknown：深灰（信息缺失，弱化处理）。 */
    val UNKNOWN = Color(0xFF616161)
}

/** 组状态 → 色点颜色 / 本地化文案（行业术语映射中文，Unknown 兜底）。 */
internal fun groupStateStyle(state: String): Pair<Color, String> = when (state.uppercase()) {
    "STABLE" -> GroupStatusStyle.STABLE to stringResource("kafka_ui_group_state_stable")
    "EMPTY" -> GroupStatusStyle.EMPTY to stringResource("kafka_ui_group_state_empty")
    "DEAD" -> GroupStatusStyle.DEAD to stringResource("kafka_ui_group_state_dead")
    "PREPARING_REBALANCE", "COMPLETING_REBALANCE" ->
        GroupStatusStyle.REBALANCE to stringResource("kafka_ui_group_state_rebalance")
    else -> GroupStatusStyle.UNKNOWN to stringResource("kafka_ui_group_state_unknown")
}

/** Lag 的千分位格式化（Locale 分组；与消息表格同一风格）。 */
internal fun formatLag(lag: Long): String = String.format("%,d", lag)

internal fun KafkaGroupInfo.toCardData(
    lagWarningThreshold: Long,
    onDetail: () -> Unit,
): ObjectCardData {
    val (dotColor, stateText) = groupStateStyle(state)
    // Lag 着色：无心跳产物不显示；0 用次要色（默认）；超阈值（阈值 > 0 才启用）警示橙。
    val lagColor = when {
        totalLag == null || totalLag == 0L -> null
        lagWarningThreshold > 0 && totalLag > lagWarningThreshold -> GroupStatusStyle.REBALANCE
        else -> null
    }
    return ObjectCardData(
        id = name,
        title = name,
        typeIcon = Icons.Default.Group,
        typeLabel = stringResource("kafka_ui_nav_groups"),
        metricValue = totalLag?.let { "Lag: ${formatLag(it)}" },
        metricColor = lagColor,
        statusIcon = Icons.Default.Circle,
        statusIconTint = dotColor,
        statusText = listOf(stateText, if (simple) stringResource("kafka_ui_group_simple") else null)
            .filterNotNull()
            .joinToString(" · "),
        actions = listOf(
            CardAction(
                id = "detail",
                icon = Icons.Default.Info,
                tooltip = "kafka_ui_group_detail",
                onClick = onDetail,
            ),
        ),
    )
}
