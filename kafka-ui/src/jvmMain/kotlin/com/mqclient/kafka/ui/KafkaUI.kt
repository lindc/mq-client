package com.mqclient.kafka.ui

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
import mq_client.kafka_ui.generated.resources.Res
import com.mqclient.uicommon.i18n.stringResource

class KafkaUI : AdapterUI {
    override val serviceId = "kafka"
    override val title = "Kafka"

    /** 模块私有 i18n 表（files/i18n/kafka_ui_<lang>.json）。 */
    override val i18nTables: List<ModuleI18nTable> = listOf(
        ModuleI18nTable(base = "kafka_ui") { language ->
            Res.readBytes("files/i18n/kafka_ui_$language.json").decodeToString()
        },
    )

    override val content: @Composable () -> Unit = { KafkaDemo() }
}

@Composable
private fun KafkaDemo() {
    var text by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var checked by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Kafka demo", style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource("kafka_demo_seed_color_note"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource("kafka_settings_title"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Bootstrap servers") },
                    placeholder = { Text("localhost:9092") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Text(stringResource("kafka_auto_commit"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Text(stringResource("kafka_sasl_ssl"))
                }
            }
        }

        Button(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource("kafka_connect"))
        }
    }
}
