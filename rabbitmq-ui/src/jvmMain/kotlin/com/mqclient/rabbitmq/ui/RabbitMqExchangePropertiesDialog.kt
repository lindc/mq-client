package com.mqclient.rabbitmq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mqclient.mqui.state.LoadState
import com.mqclient.rabbitmq.RabbitMqBindingSnapshot
import com.mqclient.rabbitmq.RabbitMqModule
import com.mqclient.uicommon.dialog.ObjectDetailDialog
import com.mqclient.uicommon.i18n.stringResource
import com.mqclient.uicommon.i18n.withArg

/**
 * 交换机属性对话框：概览（类型 / 持久化 / 自动删除 / 内部）+ **Bindings 表
 * （Destination / Routing Key / Arguments）** —— 绑定走**实时查询**
 * （[com.mqclient.rabbitmq.RabbitMqScanService.exchangeBindings]，不走慢扫缓存：
 * 路由关系是排障时要新鲜数据的地方，与 kafka 的组详情同一哲学）。
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
) {
    var bindingsState by remember(exchangeName) {
        mutableStateOf<LoadState<List<RabbitMqBindingSnapshot>>>(LoadState.Idle)
    }

    fun load() {
        bindingsState = LoadState.Loading
    }

    LaunchedEffect(connectionId, exchangeName) {
        bindingsState = LoadState.Loading
        bindingsState = runCatching {
            val service = RabbitMqModule.serviceOrNull() ?: error("服务未初始化")
            LoadState.Success(service.exchangeBindings(connectionId, exchangeName))
        }.getOrElse { LoadState.Error(failureText(it)) }
    }

    ObjectDetailDialog(
        title = stringResource("rabbitmq_ui_exchange_props_title").withArg(displayName),
        onDismiss = onDismiss,
        minWidth = 640.dp,
        minHeight = 420.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            // ---- 概览行 ----
            PropertyLine(stringResource("rabbitmq_ui_exchange_field_type"), type)
            PropertyLine(
                stringResource("rabbitmq_ui_exchange_field_durable"),
                stringResource(if (durable) "rabbitmq_ui_exchange_props_yes" else "rabbitmq_ui_exchange_props_no"),
            )
            PropertyLine(
                stringResource("rabbitmq_ui_exchange_field_auto_delete"),
                stringResource(if (autoDelete) "rabbitmq_ui_exchange_props_yes" else "rabbitmq_ui_exchange_props_no"),
            )
            if (internal) {
                PropertyLine(stringResource("rabbitmq_ui_exchange_field_internal"), stringResource("rabbitmq_ui_exchange_props_yes"))
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource("rabbitmq_ui_exchange_props_bindings_title"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
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
                        TextButton(onClick = { load() }) { Text(stringResource("rabbitmq_ui_refresh")) }
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
                        BindingsTable(bindings)
                    }
                }
            }
        }
    }
}

/** 单行概览属性：左标签右值。 */
@Composable
private fun PropertyLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** Bindings 表：Destination / Routing Key / Arguments 三列。 */
@Composable
private fun BindingsTable(bindings: List<RabbitMqBindingSnapshot>) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            TableHeader(stringResource("rabbitmq_ui_exchange_props_col_destination"), Modifier.weight(1.4f))
            TableHeader(stringResource("rabbitmq_ui_exchange_props_col_routing_key"), Modifier.weight(1.2f))
            TableHeader(stringResource("rabbitmq_ui_exchange_props_col_arguments"), Modifier.weight(1f))
        }
        HorizontalDivider()
        Column(modifier = Modifier.weight(1f)) {
            bindings.forEach { binding ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TableCell(binding.destination, Modifier.weight(1.4f))
                    TableCell(binding.routingKey.ifEmpty { "-" }, Modifier.weight(1.2f))
                    TableCell(binding.argumentsText, Modifier.weight(1f))
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

/** 失败原因：解 ExecutionException 包装（与其他调用处同一简化版）。 */
private fun failureText(error: Throwable): String =
    (error as? java.util.concurrent.ExecutionException)?.cause?.message
        ?: error.message
        ?: error::class.simpleName
        ?: "unknown"
