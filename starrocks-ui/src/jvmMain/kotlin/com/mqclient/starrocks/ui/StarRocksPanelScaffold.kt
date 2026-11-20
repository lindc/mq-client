package com.mqclient.starrocks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.starrocks.cluster.ClusterNodeRole
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.table.DataGrid
import com.mqclient.uicommon.table.DataGridColumn

/**
 * 对齐 FE Web 控制台（`:8030`）的四个系统页：**backends / ha / variables / logs**。
 *
 * 四个面板各占一个文件（`StarRocksBackendsPanel` / `StarRocksHaPanel` /
 * `StarRocksVariablesPanel` / `StarRocksLogsPanel`），本文件收它们共用的零件：
 * 外壳（标题 / 刷新 / 加载 / 错误态）、空态提示、存活单元格、列定义简写、角色短标签。
 *
 * ## 为什么是这四个、为什么这样取数
 *
 * Web 控制台的侧栏就是 system / backends / logs / queries / sessions / variables / ha。
 * 客户端已经有 queries（会话与慢查询），这四个补上剩余的系统视角。取数分两条路：
 *
 *  - **backends / ha / variables 走 SQL**（`SHOW BACKENDS` / `SHOW FRONTENDS` /
 *    `SHOW [GLOBAL] VARIABLES` / `ADMIN SHOW FRONTEND CONFIG`）—— 它们与 Web 页
 *    同源，且复用已认证的 MySQL 会话：不需要额外端口，普通部署只暴露 9030 也能用；
 *  - **logs 只能走 Web 端口**：`SHOW PROC '/log'` 在 3.4.0 不存在，FE 日志文件仅经
 *    `GET /log?file=…`（Basic Auth）暴露，端口由 `SHOW FRONTENDS` 的 HttpPort 发现。
 *
 * ## 复用关系
 *
 * backends / ha 复用 [com.mqclient.starrocks.cluster.StarRocksClusterService.loadSnapshot]
 * 与 `StarRocksNodeDetailController`（与概览、拓扑是**同一份** `SHOW FRONTENDS` /
 * `SHOW BACKENDS` 结果与同一个属性对话框），不另写一套节点模型 —— 否则同一个
 * 集群在两个页面里显示出不同的角色，就是最典型的"两套解析各错一半"。
 *
 * 外壳为什么抽出来：四张页面的工具栏逐字相同，复制四遍之后「刷新按钮在有些页面
 * 是图标、有些是文字」这类漂移一定会发生（与 [com.mqclient.uicommon.table.DataGrid]
 * 的抽出理由一致）。
 */
/* ============================== 共用零件 ============================== */

/**
 * 四个系统页共用的外壳：标题 / 副标题 / 刷新 / 降级提示 / 加载与错误态。
 *
 * 抽出来的理由与 [DataGrid] 相同：四张页面的工具栏逐字相同，复制四遍之后
 * 「刷新按钮在有些页面是图标、有些是文字」这类漂移一定会发生。
 */
@Composable
internal fun SystemPanelScaffold(
    title: String,
    subtitle: String,
    loading: Boolean,
    error: String?,
    degraded: String?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    extraActions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            extraActions()
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource("olap.starrocks.catalog.refresh"))
            }
        }

        // 降级提示：灰字一条（与概览同口径），不是红框 —— 部分数据缺失不该看起来像故障。
        degraded?.let {
            Text(
                stringResource("olap.starrocks.system.degraded").withArg(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        when {
            loading && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource("olap.starrocks.catalog.error_prefix") + error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }
            else -> content()
        }
    }
}

/** 空态 / 提示行（与各面板的次要文字同色）。 */
@Composable
internal fun ColumnScope.SystemEmptyHint(text: String) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 存活列：绿色 / 红色（语义色集中定义在 [aliveSemanticColor]）。 */
@Composable
internal fun AliveCell(alive: Boolean) {
    Text(
        text = stringResource(
            if (alive) "olap.starrocks.overview.alive" else "olap.starrocks.overview.dead",
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = if (alive) aliveSemanticColor() else MaterialTheme.colorScheme.error,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 列定义简写：省掉每处重复的泛型实参（与数据目录的 `gridColumn` 同款）。 */
internal fun <T> gridCol(
    title: String,
    weight: Float,
    cell: @Composable (T) -> Unit,
): DataGridColumn<T> = DataGridColumn(title = title, weight = weight, cell = cell)

/**
 * 角色短标签（表格里用）。
 *
 * 不复用 `olap.starrocks.node.role_*`（那是属性对话框的全称，如「BE 存储计算节点」）：
 * 在 60% 宽的面板里全称会被截成「BE 存...」，不如直接给 `BE` / `LEADER` ——
 * 这也是用户在看 `SHOW BACKENDS` 时熟悉的写法。
 */
@Composable
internal fun ClusterNodeRole.roleLabel(): String = when (this) {
    ClusterNodeRole.FE_LEADER -> stringResource("olap.starrocks.system.role_leader_short")
    ClusterNodeRole.FE_FOLLOWER -> stringResource("olap.starrocks.system.role_follower_short")
    ClusterNodeRole.FE_OBSERVER -> stringResource("olap.starrocks.system.role_observer_short")
    ClusterNodeRole.BE -> stringResource("olap.starrocks.system.role_be_short")
    ClusterNodeRole.CN -> stringResource("olap.starrocks.system.role_cn_short")
    ClusterNodeRole.CATALOG -> stringResource("olap.starrocks.system.role_catalog_short")
}
