package com.mqclient.rocketmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.rocketmq.RocketMqScanKind
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 「部分数据未刷新」提示条：这一轮对象慢扫有类型没取到时，在该连接的所有对象页顶部。
 *
 * 为什么需要它：受限账号的 ACL 会让某几类查询失败（topic 路由查不动 / 消费组看不了），
 * 这是预期内的权限边界而不是故障 —— 所以连接不该标红。但代价是界面会安静地少一块
 * 数据，用户无从区分「这里本来就没有」和「这次没读到」。
 *
 * 数据源是 [RocketMqModule.scanFailuresFlow] 而不是事件：事件是瞬时的，切个 Tab 再
 * 回来订阅者就重建了，提示会消失而数据依旧是旧的。StateFlow 与缓存同寿命，下一轮
 * 扫全或断开连接才清。（与 RabbitMQ / Kafka 同构。）
 */
@Composable
internal fun RocketMqPartialScanBanner(
    connectionId: String,
    modifier: Modifier = Modifier,
) {
    val failures by RocketMqModule.scanFailuresFlow.collectAsState()
    val kinds = scanFailureKinds(failures, connectionId)
    if (kinds.isEmpty()) return

    // 分隔符用字面量 " · "：与项目里拼多值文案的约定一致（它不需要翻译）。
    val names = kinds.joinToString(" · ") { kind -> stringResource(kind.i18nKey()) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource("rocketmq_ui_partial_scan_banner").withArg(names),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource("rocketmq_ui_partial_scan_hint"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
        )
    }
}

/**
 * 取该连接本轮失败的类型，按枚举声明顺序排（提示文案顺序因此稳定，不随 Map 迭代抖动）。
 *
 * 纯函数：不碰 Compose 运行时，可直接单测。
 */
internal fun scanFailureKinds(
    failures: Map<String, Set<RocketMqScanKind>>,
    connectionId: String,
): List<RocketMqScanKind> =
    failures[connectionId].orEmpty().sortedBy { it.ordinal }

/**
 * 失败类型 → 模块 i18n 键。
 *
 * 用 `when` 穷举而不是 `"rocketmq_ui_scan_kind_" + label`：新增一种类型时编译器会
 * 在这里报错，而不是在界面上显示裸 key 或英文单词。
 */
internal fun RocketMqScanKind.i18nKey(): String = when (this) {
    RocketMqScanKind.TOPICS -> "rocketmq_ui_scan_kind_topics"
    RocketMqScanKind.GROUPS -> "rocketmq_ui_scan_kind_groups"
}
