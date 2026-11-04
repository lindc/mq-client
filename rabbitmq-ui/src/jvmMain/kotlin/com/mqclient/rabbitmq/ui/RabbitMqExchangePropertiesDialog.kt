package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
        // 内容区最外层 Box：Snackbar 当浮层贴底，**不参与 Column 的高度分配**。
        // 旧写法把它排在 Column 末尾，状态区又是无权重子项 —— 短对话框里整条
        // 固定高度栈会溢出内容区，把 Loading 画到外壳的底部分割线 / 按钮栏上。
        Box(modifier = Modifier.fillMaxSize()) {
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
                // 状态区 `weight(1f)` 吃掉剩余高度并在内部居中（与 Kafka 属性对话框的
                // ConfigsTab 同一范式）；不能靠固定 padding 定位 —— 那样 Loading 只是
                // 跟在头部行后面的普通子项，既不居中，也压不住溢出。
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val s = bindingsState) {
                        is LoadState.Idle, is LoadState.Loading -> {
                            CircularProgressIndicator()
                        }
                        is LoadState.Error -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource("rabbitmq_ui_exchange_props_bindings_failed").withArg(s.message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(8.dp))
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
                }
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // ---- 删除交换机二次确认：**必须声明在本外壳 content lambda 内** —— compose
        // Dialog 的窗口归属随声明组合走，声明在外层会挂到主窗口、被本对话框压住
        // （实测坑，见外壳 KDoc）。----
        if (exchangeDeleteConfirm) {
            AlertDialog(
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
            AlertDialog(
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

/**
 * Bindings 表：Destination / Routing Key / Arguments / 操作 四列；onDelete 为空时无操作列。
 *
 * 视觉**逐参数对齐上半部分的 [PropertyTable]**（同一个对话框里两块表格必须像一套东西）：
 *  - 行高 `heightIn(min = 40.dp)` + `padding(horizontal = 10.dp, vertical = 8.dp)`
 *    —— 与 PropertyTable 行完全同一组数。原来只有 6dp 上下内边距：无操作列的行约
 *    28dp、带 24dp 垃圾桶按钮的行 36dp（已用渲染测试实测），两块表格一眼能看出差档；
 *  - 斑马纹：奇数行 `surfaceVariant@30%` + 4dp 圆角，偶数行透明；
 *  - 行距 8dp（`spacedBy`），**去掉行间 0.5dp 分割线** —— PropertyTable 就是靠斑马纹
 *    分行的，留着分割线会变成「斑马纹 + 横线」双层分隔；
 *  - 数据 `bodySmall` → `bodyMedium` + `onSurface`（= PropertyTable 的 Value 列）；
 *    表头 `labelMedium` → `bodyMedium` + `onSurfaceVariant`（= 同一张表的 Key 列），
 *    即「表头用键样式、数据用值样式」这对既有配对；
 *  - 水平内边距 10dp 与 PropertyTable 一致，两块的文字左边缘才对得齐（外层 Column
 *    已有 20dp，叠加后都是 30dp）。
 *
 * 列宽逻辑**不动**：Destination 1.4f / Routing Key 1.2f / Arguments 1f / 操作 56dp。
 * 等宽字体不再用于数据格（PropertyTable 不暴露字体，两块必须取舍一致）。
 */
@Composable
internal fun BindingsTable(
    bindings: List<RabbitMqBindingSnapshot>,
    onDelete: ((RabbitMqBindingSnapshot) -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BINDING_ROW_HPADDING, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BindingHeaderCell(stringResource("rabbitmq_ui_exchange_props_col_destination"), Modifier.weight(1.4f))
            BindingHeaderCell(stringResource("rabbitmq_ui_exchange_props_col_routing_key"), Modifier.weight(1.2f))
            BindingHeaderCell(stringResource("rabbitmq_ui_exchange_props_col_arguments"), Modifier.weight(1f))
            if (onDelete != null) {
                BindingHeaderCell(stringResource("rabbitmq_ui_exchange_props_col_actions"), Modifier.width(56.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        // 行区自己滚：绑定数量没有上限（实测集群里 59 条），固定高度的 Column 会
        // 溢出内容区 —— 外壳现在已经 clipToBounds，不滚就等于把后面的行直接藏了。
        // 行距 8dp 与 PropertyTable 的 spacedBy(8.dp) 一致。
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            bindings.forEachIndexed { index, binding ->
                val zebra = index % 2 == 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 测试钩子在 heightIn / padding **之前**：语义节点的 bounds 是
                        // 「此处之后全部修饰符占据的区域」，放在 padding 之后只会报出
                        // 内容区高度，行高断言就看不到真实行高。
                        .testTag(if (zebra) BINDING_ZEBRA_ROW_TAG else BINDING_PLAIN_ROW_TAG)
                        .heightIn(min = BINDING_ROW_MIN_HEIGHT)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (zebra) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .padding(horizontal = BINDING_ROW_HPADDING, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BindingDataCell(
                        if (binding.destinationType == "exchange") "[E] ${binding.destination}" else binding.destination,
                        Modifier.weight(1.4f),
                    )
                    BindingDataCell(binding.routingKey.ifEmpty { "-" }, Modifier.weight(1.2f))
                    BindingDataCell(binding.argumentsText, Modifier.weight(1f))
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
            }
        }
    }
}

/** 绑定表行高与 PropertyTable 一致（40dp）。 */
internal val BINDING_ROW_MIN_HEIGHT = 40.dp

/** 绑定表行内水平内边距：与 PropertyTable 的行内 10dp 同值，两块文字左边缘才对得齐。 */
internal val BINDING_ROW_HPADDING = 10.dp

/** 斑马纹行 / 普通行的测试标签（渲染测试用来定位行并断言行高与背景差异）。 */
internal const val BINDING_ZEBRA_ROW_TAG = "binding_zebra_row"
internal const val BINDING_PLAIN_ROW_TAG = "binding_plain_row"

/** 表头单元格：bodyMedium + 次要色 —— 与 PropertyTable 的 Key 列同一档。 */
@Composable
private fun BindingHeaderCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 数据单元格：bodyMedium + 主色 —— 与 PropertyTable 的 Value 列同一档。 */
@Composable
private fun BindingDataCell(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
