package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.i18n.stringResource

/**
 * StarRocks 占位页（**空骨架**）：显示「即将支持」文案，不做任何数据绑定、
 * 不触发连接 / 扫描 / 查询。
 *
 * Skeleton only, protocol logic pending：这是模块唯一的可见 UI，填肉时按
 * 导航项替换为真实面板（目录树 / 查询结果 / 计划树）。
 *
 * ## 复用策略
 *
 * 文案一律走 ui-common 的 `stringResource`（键前缀 `olap.starrocks.*`，
 * 见 ui-common 全局语言表），配色 / 字号一律走 MaterialTheme —— 这两项是
 * 仓库既有的共享基础能力。ui-common **没有**通用空状态组件（只有
 * `PartialScanBanner` 等提示条），因此这里用 Material3 基础组件拼装，
 * 不新造可复用组件（避免空壳阶段引入未经使用的抽象）。
 */
@Composable
fun StarRocksPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource("olap.starrocks.placeholder_title"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource("olap.starrocks.placeholder_body"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
