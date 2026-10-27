package com.mqclient.uicommon

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 「部分数据未刷新」提示条（**四个 MQ 模块共用**）。
 *
 * 场景：本轮对象扫描有类型没取到 —— 受限账号的权限边界（读不到 nodes / 某几类
 * topic）或单类查询超时。连接不该标红（那不是故障），但界面会安静地少一块数据，
 * 用户无从区分「这里本来就没有」和「这次没读到」。这条提示把那个区分还给用户。
 *
 * 为什么下沉到这里：IBM MQ / Kafka / RabbitMQ / RocketMQ 的四个实现曾经是逐字副本
 * （同样的 Row/Icon/Text、同样的 tertiaryContainer 配色、同样的两个 i18n 键命名规则），
 * 唯一差异是「失败类型如何翻译成本地化名字」。那份差异留在各模块（类型枚举归各协议），
 * 外壳只留一份 —— 改配色 / 加图标只需要动这里。
 *
 * 数据源约定（调用方负责）：从模块的 `scanFailuresFlow` collect，按 connectionId
 * 过滤、按枚举声明顺序排序后传进来。用 StateFlow 而不是事件：事件是瞬时的，切个
 * Tab 再回来订阅者就重建了，提示会消失而数据依旧是旧的。
 *
 * @param failureNames 已本地化的失败类型名（空 = 不显示提示条）。
 * @param messageKey 提示文案的 i18n 键，`%1$s` 是 ` · ` 连接起来的类型名。
 * @param hintKey 右侧灰色补充说明的 i18n 键。
 */
@Composable
fun PartialScanBanner(
    failureNames: List<String>,
    messageKey: String,
    hintKey: String,
    modifier: Modifier = Modifier,
) {
    if (failureNames.isEmpty()) return
    // 分隔符用字面量 " · "：与项目里拼多值文案的约定一致（它不需要翻译）。
    val names = failureNames.joinToString(" · ")
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
            text = stringResource(messageKey).withArg(names),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(hintKey),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
        )
    }
}
