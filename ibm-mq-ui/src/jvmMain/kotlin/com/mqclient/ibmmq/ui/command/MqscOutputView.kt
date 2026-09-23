package com.mqclient.ibmmq.ui.command

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.ibmmq.command.model.MqscResult
import com.mqclient.uicommon.i18n.stringResource

/**
 * 输出区：逐条结果卡片（[MqscResultItem]）；没有结果时给空态文案。
 */
@Composable
fun MqscOutputView(
    results: List<MqscResult>,
    modifier: Modifier = Modifier,
) {
    if (results.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource("mqsc_output_empty"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(results) { result ->
                MqscResultItem(result = result)
            }
        }
    }
}
