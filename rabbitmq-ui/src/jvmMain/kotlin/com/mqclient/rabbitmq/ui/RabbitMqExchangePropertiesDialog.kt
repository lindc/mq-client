package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.core.ConnectionStatus
import com.mqclient.core.concurrent.runCatchingCancellable
import com.mqclient.uicommon.command.ToolbarButton
import com.mqclient.mqui.state.LoadState
import com.mqclient.rabbitmq.RabbitMqBindingSnapshot
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.uicommon.DangerButton
import com.mqclient.uicommon.PropertyItem
import com.mqclient.uicommon.PropertyTable
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg
import com.mqclient.uicommon.failureText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 交换机属性对话框：概览（类型 / 持久化 / 自动删除 / 内部）+ **Bindings 表
 * （Destination / Routing Key / Arguments）** —— 绑定走**实时查询**
 * （[com.mqclient.rabbitmq.RabbitMqScanService.exchangeBindings]，不走慢扫缓存：
 * 路由关系是排障时要新鲜数据的地方，与 kafka 的组详情同一哲学）。
 *
 * 删除：底部 danger 按钮（**默认交换机 / amq.* / internal 直接隐藏**，与 Kafka
 * 内部主题隐藏同一约定）→ AlertDialog 二次确认（**必须声明在本外壳 content lambda
 * 内** —— compose Dialog 窗口归属随声明组合走）→ 成功关对话框 + [onDeleted] 回调；
 * 失败走对话框内 Snackbar。
 *
 * @param onDeleted 删除成功后回调（宿主刷新列表 / 关对话框 + Snackbar 提示）。
 */
@Composable
fun RabbitMqExchangePropertiesDialog(
    connectionId: String,
    exchangeName: String,
    displayName: String,
    type: String,
    durable: Boolean,
    autoDelete: Boolean,
    internal: Boolean,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val systemExchange = exchangeName.isEmpty() || exchangeName.startsWith("amq.")
    var bindingsState by remember(exchangeName) {
        mutableStateOf<LoadState<List<RabbitMqBindingSnapshot>>>(LoadState.Idle)
    }
    var deleteConfirm by remember(exchangeName) { mutableStateOf<RabbitMqBindingSnapshot?>(null) }
    var deleting by remember(exchangeName) { mutableStateOf(false) }
    // 交换机本体删除（footer danger 按钮）的确认状态，与绑定删除互不相干。
    var exchangeDeleteConfirm by remember(exchangeName) { mutableStateOf(false) }
    var showAddBinding by remember(exchangeName) { mutableStateOf(false) }
    // 绑定增删成功后的本地重载触发器（实时列表不等慢扫缓存）。
    var bindingsReload by remember(exchangeName) { mutableStateOf(0) }

    // 打开瞬间可能恰好处于 Connecting / 刚从抖动恢复：错误不能粘住。
    val connectionStatus = rememberRabbitMqConnectionStatus(connectionId)

    /**
     * 重新拉一次绑定。
     *
     * **必须动 [bindingsReload]**，不能只把状态改成 Loading：真正发请求的是下面那个
     * `LaunchedEffect(connectionId, exchangeName, bindingsReload)`，只改状态不会重跑
     * 它 —— 旧写法（`fun load() { bindingsState = Loading }`）让错误态的「刷新」按钮
     * 把界面打进永久转圈。
     */
    fun load() {
        bindingsState = LoadState.Loading
        bindingsReload++
    }

    fun reload() {
        bindingsReload++
    }

    /** 删除绑定（danger 确认后调用）：成功重载本表 + 触发 scanNow 同步慢扫缓存。 */
    fun deleteBinding(binding: RabbitMqBindingSnapshot) {
        scope.launch {
            val service = RabbitMqModule.serviceOrNull() ?: error("服务未初始化")
            deleting = true
            try {
                val propertiesKey = binding.propertiesKey ?: error("缺少删除凭据（properties_key）")
                service.deleteBinding(connectionId, exchangeName, binding.destination, binding.destinationType, propertiesKey)
                deleting = false
                deleteConfirm = null
                bindingsReload++
                RabbitMqModule.scanNow(connectionId)
                snackbar.showSnackbar(stringResource("rabbitmq_ui_binding_deleted"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deleting = false
                snackbar.showSnackbar(
                    stringResource("rabbitmq_ui_exchange_delete_failed").withArg(failureText(e)),
                )
            }
        }
    }

    /** 删除交换机（footer danger 按钮）：成功关对话框 + onDeleted 回调。 */
    fun deleteExchangeEntry() {
        scope.launch {
            val service = RabbitMqModule.serviceOrNull() ?: error("服务未初始化")
            deleting = true
            try {
                service.deleteExchange(connectionId, exchangeName)
                deleting = false
                onDismiss()
                onDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deleting = false
                snackbar.showSnackbar(
                    stringResource("rabbitmq_ui_exchange_delete_failed").withArg(failureText(e)),
                )
            }
        }
    }

    LaunchedEffect(connectionId, exchangeName, bindingsReload) {
        bindingsState = LoadState.Loading
        bindingsState = runCatchingCancellable {
            val service = RabbitMqModule.serviceOrNull() ?: error("服务未初始化")
            LoadState.Success(service.exchangeBindings(connectionId, exchangeName))
        }.getOrElse { LoadState.Error(failureText(it)) }
    }

    // 时序兜底：连接落定成 Connected 而绑定仍在错误态时自动重拉（与队列属性对话框同口径）。
    LaunchedEffect(connectionStatus) {
        if (connectionStatus == ConnectionStatus.Connected && bindingsState is LoadState.Error) {
            reload()
        }
    }

    ObjectDetailDialog(
        title = stringResource("rabbitmq_ui_exchange_props_title").withArg(displayName),
        onDismiss = onDismiss,
        minWidth = 640.dp,
        minHeight = 420.dp,
        footerActions = {
            // 默认交换机 / amq.* / internal 禁止删除：按钮直接隐藏（与 Kafka 内部主题同一约定）。
            if (!systemExchange && !internal) {
                DangerButton(onClick = { exchangeDeleteConfirm = true }) {
                    Text(stringResource("rabbitmq_ui_exchange_props_delete"))
                }
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton(
                onClick = onDismiss,
            ) { Text(stringResource("common_close")) }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            // ---- 概览区（统一 PropertyTable：斑马纹 / 左对齐 / 可复制）----
            PropertyTable(
                items = listOf(
                    PropertyItem(stringResource("rabbitmq_ui_exchange_props_field_type"), type),
                    PropertyItem(
                        stringResource("rabbitmq_ui_exchange_field_durable"),
                        stringResource(if (durable) "rabbitmq_ui_exchange_props_yes" else "rabbitmq_ui_exchange_props_no"),
                    ),
                    PropertyItem(
                        stringResource("rabbitmq_ui_exchange_field_auto_delete"),
                        stringResource(if (autoDelete) "rabbitmq_ui_exchange_props_yes" else "rabbitmq_ui_exchange_props_no"),
                    ),
                    PropertyItem(
                        stringResource("rabbitmq_ui_exchange_field_internal"),
                        stringResource(if (internal) "rabbitmq_ui_exchange_props_yes" else "rabbitmq_ui_exchange_props_no"),
                    ),
                ),
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource("rabbitmq_ui_exchange_props_bindings_title"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))

            // ---- Bindings 区：头部行（计数 + 添加）+ 表格（实时，系统交换机禁写）----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource("rabbitmq_ui_exchange_props_bindings_count")
                        .withArg((bindingsState as? LoadState.Success)?.data?.size ?: 0),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                if (!systemExchange) {
                    ToolbarButton(
                        icon = Icons.Default.Add,
                        text = stringResource("rabbitmq_ui_exchange_bindings_add"),
                        onClick = { showAddBinding = true },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            // ---- Bindings 表（实时）----
            when (val s = bindingsState) {
                is LoadState.Idle, is LoadState.Loading -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is LoadState.Error -> {
                    Column {
                        Text(
                            text = stringResource("rabbitmq_ui_exchange_props_bindings_failed").withArg(s.message),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        SecondaryButton(onClick = { load() }) { Text(stringResource("rabbitmq_ui_refresh")) }
                    }
                }
                is LoadState.Success<List<RabbitMqBindingSnapshot>> -> {
                    val bindings = s.data
                    if (bindings.isEmpty()) {
                        Text(
                            text = stringResource("rabbitmq_ui_exchange_props_bindings_empty"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        BindingsTable(
                            bindings = bindings,
                            onDelete = if (systemExchange) null else { binding -> deleteConfirm = binding },
                        )
                    }
                }
            }
            SnackbarHost(hostState = snackbar)
        }

        // ---- 删除交换机二次确认：**必须声明在本外壳 content lambda 内** —— compose
        // Dialog 的窗口归属随声明组合走，声明在外层会挂到主窗口、被本对话框压住
        // （实测坑，见外壳 KDoc）。----
        if (exchangeDeleteConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { exchangeDeleteConfirm = false },
                title = { Text(stringResource("rabbitmq_ui_exchange_delete_confirm_title")) },
                text = {
                    Text(
                        text = stringResource("rabbitmq_ui_exchange_delete_confirm_text")
                            .withArg(exchangeName),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    DangerButton(
                        onClick = {
                            exchangeDeleteConfirm = false
                            deleteExchangeEntry()
                        },
                        enabled = !deleting,
                    ) {
                        Text(
                            text = stringResource(
                                if (deleting) "rabbitmq_ui_exchange_deleting" else "rabbitmq_ui_exchange_delete_confirm",
                            ),
                        )
                    }
                },
                dismissButton = {
                    SecondaryButton(
                        onClick = { exchangeDeleteConfirm = false },
                    ) { Text(stringResource("common_cancel")) }
                },
            )
        }

        // ---- 删除绑定二次确认（行内垃圾桶触发）----
        deleteConfirm?.let { binding ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { deleteConfirm = null },
                title = { Text(stringResource("rabbitmq_ui_binding_delete_confirm_title")) },
                text = {
                    Text(
                        text = stringResource("rabbitmq_ui_binding_delete_confirm_text")
                            .withArg(exchangeName, binding.destination),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    DangerButton(
                        onClick = { deleteBinding(binding) },
                        enabled = !deleting,
                    ) { Text(stringResource("rabbitmq_ui_exchange_delete_confirm")) }
                },
                dismissButton = {
                    SecondaryButton(
                        onClick = { deleteConfirm = null },
                    ) { Text(stringResource("common_cancel")) }
                },
            )
        }

        // ---- 添加绑定弹窗（嵌套声明在本外壳 content lambda 内）----
        if (showAddBinding) {
            RabbitMqAddBindingDialog(
                connectionId = connectionId,
                exchangeName = exchangeName,
                exchangeType = type,
                onDismiss = { showAddBinding = false },
                onCreated = {
                    showAddBinding = false
                    bindingsReload++
                    RabbitMqModule.scanNow(connectionId)
                },
            )
        }
    }
}

/** Bindings 表：Destination / Routing Key / Arguments / 操作 四列；onDelete 为空时无操作列。 */
@Composable
private fun BindingsTable(
    bindings: List<RabbitMqBindingSnapshot>,
    onDelete: ((RabbitMqBindingSnapshot) -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            TableHeader(stringResource("rabbitmq_ui_exchange_props_col_destination"), Modifier.weight(1.4f))
            TableHeader(stringResource("rabbitmq_ui_exchange_props_col_routing_key"), Modifier.weight(1.2f))
            TableHeader(stringResource("rabbitmq_ui_exchange_props_col_arguments"), Modifier.weight(1f))
            if (onDelete != null) {
                TableHeader(stringResource("rabbitmq_ui_exchange_props_col_actions"), Modifier.width(56.dp))
            }
        }
        HorizontalDivider()
        Column(modifier = Modifier.weight(1f)) {
            bindings.forEach { binding ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TableCell(
                        if (binding.destinationType == "exchange") "[E] ${binding.destination}" else binding.destination,
                        Modifier.weight(1.4f),
                    )
                    TableCell(binding.routingKey.ifEmpty { "-" }, Modifier.weight(1.2f))
                    TableCell(binding.argumentsText, Modifier.weight(1f))
                    if (onDelete != null) {
                        Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) {
                            IconButton(onClick = { onDelete(binding) }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Default.DeleteSweep,
                                    contentDescription = stringResource("rabbitmq_ui_exchange_props_binding_delete"),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

/** 单元格文本（等宽字体，长内容省略）。 */
@Composable
private fun TableCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 表头文本。 */
@Composable
private fun TableHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

