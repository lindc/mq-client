@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.mqclient.uicommon.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.LanguageResources
import com.mqclient.uicommon.i18n.stringResource
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * 日期 + 时间选择弹窗（原 ibm-mq MessageBrowseTab 的私有组件上移公共化，Kafka 浏览
 * Tab / 重置位移等处复用）：分两步（先日期后时间）—— M3 DatePicker 在桌面上的绘制
 * 宽度会超出测量宽度（压到旁边组件），竖排又超出对话框高度，并排 / 滚动都不可靠。
 *
 * @param title 弹窗标题（调用方用自己的 i18n key 取好传入）。
 * @param initial 初始选中值；null 不预选日期（日期步的「下一步」禁用直到选了日期）。
 * @param onConfirm 确认回调，分钟粒度的 [LocalDateTime]（调用方自行换算 epoch millis）。
 */
@Composable
fun DateTimePickerDialog(
    title: String,
    initial: LocalDateTime?,
    onConfirm: (LocalDateTime) -> Unit,
    onDismiss: () -> Unit,
) {
    // material3 内置文案（月份 / 星期 / Select date…）经 PlatformLocaleDelegate 读
    // JVM 默认 locale，不认项目自己的 i18n 表 —— 这里对齐应用语言，缺翻译的键回落英文。
    val appLanguage = LanguageResources.currentLanguage() ?: LanguageResources.LANGUAGE_ZH_CN
    remember(appLanguage) {
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag(appLanguage))
        true
    }
    // DatePicker 的毫秒值是 UTC 零点；经 epochDay 换算避免时区偏移差一天。
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initial?.toLocalDate()?.toEpochDay()?.times(86_400_000L),
    )
    val timeState = rememberTimePickerState(
        initialHour = initial?.hour ?: 0,
        initialMinute = initial?.minute ?: 0,
        is24Hour = true,
    )
    var phase by remember { mutableStateOf(PickerPhase.DATE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when (phase) {
                PickerPhase.DATE -> DatePicker(state = dateState, showModeToggle = false)
                PickerPhase.TIME -> TimePicker(state = timeState)
            }
        },
        confirmButton = {
            when (phase) {
                PickerPhase.DATE -> PressableButton(
                    onClick = { phase = PickerPhase.TIME },
                    enabled = dateState.selectedDateMillis != null,
                ) { Text(stringResource("common_next")) }
                PickerPhase.TIME -> PressableButton(onClick = {
                    val millis = dateState.selectedDateMillis ?: return@PressableButton
                    val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    onConfirm(LocalDateTime.of(date, LocalTime.of(timeState.hour, timeState.minute)))
                }) { Text(stringResource("common_ok")) }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 时间步可退回改日期；日期步只有取消。
                if (phase == PickerPhase.TIME) {
                    PressableButton(
                        onClick = { phase = PickerPhase.DATE },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) { Text(stringResource("common_back")) }
                }
                PressableButton(
                    onClick = onDismiss,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) { Text(stringResource("common_cancel")) }
            }
        },
    )
}

private enum class PickerPhase { DATE, TIME }
