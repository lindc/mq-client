package com.mqclient.uicommon.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 一条键值对编辑草稿（消息 Headers / 用户属性共用的行模型）：[valueIsHex] 勾上时
 * 宿主按 hex 解码成字节发送（RocketMQ 的用户属性是纯字符串，不勾即可）。
 */
data class HeaderDraft(
    val key: String = "",
    val value: String = "",
    val valueIsHex: Boolean = false,
)

/**
 * Headers / 用户属性键值对编辑器（IBM MQ / Kafka / RocketMQ 发送页共用）：
 * 一行一个键值对，右侧加/删按钮。行间距 8dp（与各模块的项目表单惯例一致）。
 * 文案由宿主传入（各模块 i18n 自管），布局与样式统一在本组件 —— 各模块发送页的
 * 该区域外观 100% 一致。
 *
 * @param hexText 「按 HEX 发送」勾选的文案；传空串则不渲染 hex 勾选（RocketMQ 的
 *   用户属性是纯字符串，勾 hex 无意义）。
 */
@Composable
fun HeadersEditor(
    headers: List<HeaderDraft>,
    onHeadersChange: (List<HeaderDraft>) -> Unit,
    title: String,
    addText: String,
    keyPlaceholder: String,
    valuePlaceholder: String,
    hexText: String,
    removeText: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { onHeadersChange(headers + HeaderDraft()) },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = addText,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        headers.forEachIndexed { index, draft ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft.key,
                    onValueChange = { onHeadersChange(headers.mapIndexed { i, h -> if (i == index) h.copy(key = it) else h }) },
                    placeholder = { Text(keyPlaceholder) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(0.8f),
                )
                OutlinedTextField(
                    value = draft.value,
                    onValueChange = { onHeadersChange(headers.mapIndexed { i, h -> if (i == index) h.copy(value = it) else h }) },
                    placeholder = { Text(valuePlaceholder) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1.2f),
                )
                if (hexText.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = hexText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Checkbox(
                            checked = draft.valueIsHex,
                            onCheckedChange = { checked ->
                                onHeadersChange(headers.mapIndexed { i, h -> if (i == index) h.copy(valueIsHex = checked) else h })
                            },
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                IconButton(
                    onClick = { onHeadersChange(headers.filterIndexed { i, _ -> i != index }) },
                    enabled = headers.size > 1,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = removeText,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
