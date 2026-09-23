package com.mqclient.pulsar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.core.AdapterUI
import com.mqclient.core.ModuleI18nTable
import com.mqclient.uicommon.i18n.stringResource
import mq_client.pulsar_ui.generated.resources.Res

class PulsarUI : AdapterUI {
    override val serviceId = "pulsar"
    override val title = "Pulsar"

    /**
     * 本模块私有 i18n 表（`files/i18n/pulsar_<lang>.json`）：占位页文案归自己的资源文件，
     * 加翻译不需要动 ui-common 的公共表。读取必须用自己的 [Res]
     * （compose 运行时资源按库包名隔离，见 [ModuleI18nTable]）。
     */
    override val i18nTables: List<ModuleI18nTable> = listOf(
        ModuleI18nTable(base = "pulsar") { language ->
            Res.readBytes("files/i18n/pulsar_$language.json").decodeToString()
        },
    )

    override val content: @Composable () -> Unit = { PulsarDemo() }
}

@Composable
private fun PulsarDemo() {
    var serviceUrl by remember { mutableStateOf("") }
    var tenant by remember { mutableStateOf("") }
    var namespace by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var checked by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource("pulsar_demo_title"), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource("pulsar_demo_hint"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource("pulsar_settings"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = serviceUrl,
                    onValueChange = { serviceUrl = it },
                    label = { Text(stringResource("pulsar_service_url")) },
                    placeholder = { Text(stringResource("pulsar_service_url_ph")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tenant,
                    onValueChange = { tenant = it },
                    label = { Text(stringResource("pulsar_tenant")) },
                    placeholder = { Text(stringResource("pulsar_tenant_ph")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = namespace,
                    onValueChange = { namespace = it },
                    label = { Text(stringResource("pulsar_namespace")) },
                    placeholder = { Text(stringResource("pulsar_namespace_ph")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Text(stringResource("pulsar_enable_batch"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Text(stringResource("pulsar_enable_tls"))
                }
            }
        }

        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource("pulsar_connect"))
        }
    }
}
