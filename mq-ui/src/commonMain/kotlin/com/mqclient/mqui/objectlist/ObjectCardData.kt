package com.mqclient.mqui.objectlist

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 对象卡片的数据模型（纯数据，由各 MQ 模块把自己的对象转换而来）。
 *
 * 状态不走枚举 —— 每个 MQ 的「状态」语义不同，各模块直接给
 * [statusIcon] + [statusIconTint]（如 CheckCircle 绿 = 运行中、Warning 橙 =
 * 接近满、Error 红 = 严重延迟），想表达什么就表达什么。
 * 全部槽位可选 —— null 的槽位在 [ObjectCard] 里不渲染，布局自动补位。
 */
data class ObjectCardData(
    /** 对象唯一 ID（双击 / 菜单动作按它定位对象；也是网格的稳定 key）。 */
    val id: String,
    /** 对象名字（单行，超出省略）。 */
    val title: String,
    /** 第 1 行：类型图标；null 不显示。 */
    val typeIcon: ImageVector? = null,
    /** 类型图标悬停文案（如「发送通道」/「未知类型」）；null 不显示 tooltip。 */
    val typeLabel: String? = null,
    /** 第 2 行：状态图标（各模块自定义语义）；null 不显示。 */
    val statusIcon: ImageVector? = null,
    /** 状态图标（与状态文字）的颜色；null 用主题的 onSurfaceVariant。 */
    val statusIconTint: Color? = null,
    /** 第 2 行：状态文字；null 不显示。 */
    val statusText: String? = null,
    /** 右侧数值（如队列深度 / 消息数）；null 不显示。 */
    val metricValue: String? = null,
    /**
     * 右上角数值的颜色；null 用主题的 onSurfaceVariant（次要色）。告警语义时给
     * 警示色（如消费组 Lag 超阈值）—— null 时与普通数值无异。
     */
    val metricColor: Color? = null,
    /** 第 1 行的操作按钮（[CardAction]，由各 MQ 模块构造）；空则不渲染。 */
    /**
     * 第 2 行 actions 左侧的纯展示指标（icon + 数字/短文案，不可点击，如消费者数）。
     * 空则不渲染，各模块不传即维持原布局。
     */
    val indicators: List<ObjectCardIndicator> = emptyList(),
    /**
     * 告警边框色（如积压无消费者的警告橙）：非空时卡片默认态与悬停态的边框都用它
     * （悬停保留告警，交互反馈靠阴影抬升），按下态仍走 primary。null = 常规边框。
     */
    val alertTint: Color? = null,
    val actions: List<CardAction> = emptyList(),
    /**
     * 正在执行的动作 id 集合（[CardAction.id]）。命中的按钮显示内联转圈并禁用点击，
     * 用于「启动 / 停止」这类异步动作——执行期间不允许重复触发，也给出可见的反馈。
     */
    val executingActionIds: Set<String> = emptySet(),
)

/**
 * 纯展示指标：icon + 数字/短文案，渲染在 actions 左侧（第 2 行右侧区），不可点击。
 * [tint] 同时作用于图标与文字；null 用主题 onSurfaceVariant（次要灰）。
 */
data class ObjectCardIndicator(
    val icon: ImageVector,
    val label: String,
    val tint: Color? = null,
)
