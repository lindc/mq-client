package com.mqclient.rocketmq.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.rocketmq.RocketMqBrokerSnapshot
import com.mqclient.rocketmq.RocketMqModule
import com.mqclient.uicommon.PropertyItem
import com.mqclient.uicommon.PropertyTableSection
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * Broker 属性对话框（RocketMQ 拓扑图双击 `b:<brokerName>` 节点打开）：
 * [ObjectDetailDialog] 外壳 + [PropertyTableSection] 展示集群慢扫缓存里的
 * Broker 元信息。
 *
 * 拓扑节点按 brokerName 聚合，一个 brokerName 下可能有 Master（ID 0）与多个
 * Slave（ID 1..n）。因此顶部先给「集群信息 + 实例数」，再按 brokerId 升序为
 * 每个实例渲染一组属性（Broker ID / 角色 / IP / 端口 / 完整地址）。
 *
 * 数据只读 [RocketMqModule.serviceOrNull] 的 `cachedClusterInfo`，与集群概览 /
 * 拓扑图同源，不发起额外 RPC。运行时信息（版本号 / 启动时间 / 磁盘使用率）
 * 不在当前 ClusterInfo 快照内，暂不展示；缓存缺失时给出重新扫描提示。
 */
@Composable
fun RocketMqBrokerPropertiesDialog(
    connectionId: String,
    brokerName: String,
    onDismiss: () -> Unit,
) {
    val cluster = remember(connectionId) {
        RocketMqModule.serviceOrNull()?.cachedClusterInfo(connectionId)
    }
    val instances = remember(cluster, brokerName) {
        brokerInstances(cluster?.brokers.orEmpty(), brokerName)
    }

    ObjectDetailDialog(
        title = stringResource("rocketmq_ui_broker_props_title").withArg(brokerName),
        onDismiss = onDismiss,
        minWidth = 520.dp,
        minHeight = 400.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            PropertyTableSection(
                title = stringResource("rocketmq_ui_overview_cluster"),
                items = listOf(
                    PropertyItem(stringResource("rocketmq_ui_overview_col_name"), brokerName),
                    PropertyItem(
                        stringResource("rocketmq_ui_overview_cluster_name"),
                        instances.map { it.cluster }.distinct().joinToString(", ").ifBlank { null },
                    ),
                    PropertyItem(
                        stringResource("rocketmq_ui_broker_props_instances"),
                        instances.size.toString(),
                    ),
                ),
            )

            if (instances.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource("rocketmq_ui_broker_props_no_data"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                instances.forEach { broker ->
                    Spacer(Modifier.height(16.dp))
                    val (ip, port) = brokerAddressParts(broker.address)
                    PropertyTableSection(
                        title = stringResource("rocketmq_ui_broker_props_section_instance")
                            .withArg("${broker.brokerId} · ${broker.role}"),
                        items = listOf(
                            PropertyItem(
                                stringResource("rocketmq_ui_broker_props_id"),
                                broker.brokerId.toString(),
                            ),
                            PropertyItem(
                                stringResource("rocketmq_ui_overview_col_role"),
                                broker.role,
                            ),
                            PropertyItem(stringResource("rocketmq_ui_broker_props_ip"), ip),
                            PropertyItem(stringResource("rocketmq_ui_broker_props_port"), port),
                            PropertyItem(
                                stringResource("rocketmq_ui_overview_col_addr"),
                                broker.address,
                            ),
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 拓扑节点按 brokerName 聚合：取出该名称下的全部实例，按 brokerId 升序（Master 在前）。 */
internal fun brokerInstances(
    brokers: List<RocketMqBrokerSnapshot>,
    brokerName: String,
): List<RocketMqBrokerSnapshot> =
    brokers.filter { it.brokerName == brokerName }.sortedBy { it.brokerId }

/**
 * 拆分 Broker 地址为 IP / 端口。RocketMQ 的 brokerAddrs 是 `host:port`；
 * 没有端口段时端口回落 `-`（交给 [PropertyTable] 统一展示空值）。
 * IPv6 形如 `[::1]:10911`，取最后一个冒号，方括号保留在 IP 内。
 */
internal fun brokerAddressParts(address: String): Pair<String, String> {
    val trimmed = address.trim()
    val separator = trimmed.lastIndexOf(':')
    if (separator <= 0 || separator == trimmed.lastIndex) return trimmed to "-"
    return trimmed.substring(0, separator) to trimmed.substring(separator + 1)
}
