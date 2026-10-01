package com.mqclient.ibmmq.ui.topology

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mqclient.uicommon.i18n.stringResource

/**
 * 集群关系面板的枚举值 → 显示文案集中映射（本 Tab 专用，唯一的 UI 层映射点）。
 *
 * service 侧只返回稳定枚举值（REPOS / NORMAL / CLUSSDR / CLUSSDRA / RUNNING …）；
 * 文案统一走 i18n（ibm_mq_*.json），key 缺失时 stringResource 回落显示 key 本身
 * ——所以未知值返回「原始值(key 本身)」而不是空白。
 *
 * 注意：allclient 10.0.0.5 缺 MQQMT_NORMAL 常量（同 MQLSR_* 先例），service 侧
 * 的仓库类型值可能是原始数字（"1" / "0"），这里一并按值映射。
 */

/** 仓库类型：REPOS / 1 → 完整仓库；NORMAL / 0 → 部分仓库；其他 → 原始值(未知)。 */
@Composable
internal fun clusterQmTypeLabel(value: String?): String = when (value?.uppercase()) {
    null, "", "-" -> "-"
    "REPOS", "REPOSITORY", "1" -> stringResource("cluster_enum_qmtype_repos")
    "NORMAL", "0" -> stringResource("cluster_enum_qmtype_normal")
    else -> unknownValue(value)
}

/** 通道定义类型：CLUSSDR（手动）/ CLUSSDRA（自动）/ CLUSRCVR（集群接收方）。 */
@Composable
internal fun clusterDefTypeLabel(value: String?): String = when (value?.uppercase()) {
    null, "", "-" -> "-"
    "CLUSSDR" -> stringResource("cluster_enum_deftype_clussdr")
    "CLUSSDRA" -> stringResource("cluster_enum_deftype_clussdra")
    "CLUSRCVR" -> stringResource("cluster_enum_deftype_clusrcvr")
    else -> unknownValue(value)
}

/** 通道运行状态：与通道列表页的状态语义一致。 */
@Composable
internal fun clusterStatusLabel(value: String?): String = when (value?.uppercase()) {
    null, "", "-" -> "-"
    "RUNNING" -> stringResource("cluster_enum_status_running")
    "RETRYING" -> stringResource("cluster_enum_status_retrying")
    "STOPPED" -> stringResource("cluster_enum_status_stopped")
    "STARTING" -> stringResource("cluster_enum_status_starting")
    "STOPPING" -> stringResource("cluster_enum_status_stopping")
    "INACTIVE" -> stringResource("cluster_enum_status_inactive")
    "PAUSED" -> stringResource("cluster_enum_status_paused")
    else -> unknownValue(value)
}

/** 状态列颜色：RUNNING 绿 / RETRYING 警告橙 / STOPPED 错误红，其余默认文字色。 */
@Composable
internal fun clusterStatusColor(value: String?): Color = when (value?.uppercase()) {
    "RUNNING" -> Color(0xFF22C55E)
    "RETRYING" -> Color(0xFFED6C02)
    "STOPPED" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun unknownValue(value: String): String =
    "${value.trim()} (${stringResource("cluster_enum_unknown")})"
