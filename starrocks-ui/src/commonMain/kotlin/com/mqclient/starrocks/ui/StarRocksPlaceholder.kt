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
 * StarRocks 的**空白态**页面：显示模块标题与提示文案，不读数据、不触网。
 *
 * 两处用途：
 *  - `AdapterUI.content` —— 连接卡片被展开但还没选具体 Tab 时的占位；
 *  - `contentFor` 遇到**未知的 objectType** 时的兜底（未来的新导航项在实现前
 *    也是这个页面，而不是白屏）。
 *
 * 真实面板（目录树 / 查询结果 / 集群视图…）都在 jvmMain，经 `contentFor` 分发。
 *
 * ## 复用策略
 *
 * 文案走 ui-common 的 `stringResource`（键前缀 `olap.starrocks.*`，由本模块的
 * 私有语言表提供），配色 / 字号一律走 MaterialTheme。ui-common **没有**通用
 * 空状态组件（只有 `PartialScanBanner` 等提示条），因此这里用 Material3 基础
 * 组件拼装，不为一个占位页新造抽象。
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
