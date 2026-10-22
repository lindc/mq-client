package com.mqclient.app.settings

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import com.mqclient.core.Config
import dev.nucleusframework.application.LocalNucleusWindow
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.SecondaryButton
import com.mqclient.uicommon.i18n.stringResource
import dev.nucleusframework.application.DecoratedDialog
import dev.nucleusframework.window.windowDragArea

/** Tabs the side nav can select. Order matches visual order top → bottom. */
internal enum class SettingsTab(val icon: ImageVector, val key: String) {
    GENERAL(Icons.Default.Settings, "settings_tab_general"),
    APPEARANCE(Icons.Default.Palette, "settings_tab_appearance"),
    AI(Icons.Default.Android, "settings_tab_ai"),
    ;

    val tabName: String get() = stringResource(key)
}

/**
 * App-wide settings dialog. Three-tab layout: left rail picks [SettingsTab],
 * right pane shows the corresponding composable, bottom row holds the
 * global Save / Cancel actions.
 *
 * Holds a local draft [Config] so edits don\'t leak back until the user
 * hits Save; Cancel (via the title-bar close button) just
 * drops the draft.
 *
 * [onSave] returns `null` when the write actually landed (the caller then
 * closes us), or a user-facing message when it didn\'t - in which case we
 * stay open and show the message instead of pretending it worked.
 */
@Composable
fun SettingsDialog(
    initial: Config,
    onDismiss: () -> Unit,
    onSave: (Config) -> String?,
) {
    var draft by remember { mutableStateOf(initial) }
    var selectedTab by remember { mutableStateOf(SettingsTab.GENERAL) }
    var saveError by remember { mutableStateOf<String?>(null) }
    // 关闭拦截：草稿相对打开时的 initial 改过 → ESC / ✕ / 取消先弹确认，不直接丢改动。
    // 保存成功后 caller 把落盘配置传回来，draft == initial 自然变回「干净」。
    // 拦截决策全在 [SettingsCloseGuard]（可单测），这里只做接线。
    val isDirty = draft != initial
    val closeGuard = remember { SettingsCloseGuard(onClose = onDismiss) }

    // A real (Nucleus) window instead of the old fixed-size popup, so the user
    // can drag any edge or corner to resize it. The Surface fills the whole
    // window, so its content tracks whatever size is picked.
    // Esc 关闭走 onPreviewKeyEvent：对话框没有系统标题栏，键盘出口得自己接。
    DecoratedDialog(
        // Alt+F4 / 系统关闭请求和 ESC / ✕ / 取消走同一条 guard 路径：有改动先确认。
        onCloseRequest = { closeGuard.requestClose(isDirty) },
        state = rememberDialogState(size = DpSize(760.dp, 600.dp)),
        title = stringResource("settings_title"),
        resizable = true,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
                closeGuard.requestClose(isDirty)
                true
            } else {
                false
            }
        },
    ) {
        // Keep the window usable: don't let it shrink below what the side rail
        // + tab content need. 放 effect 里：组合体会随重组反复执行，窗口副作用不该在那儿。
        val nucleusWindow = LocalNucleusWindow.current
        LaunchedEffect(nucleusWindow) { nucleusWindow.setMinimumSize(DpSize(720.dp, 480.dp)) }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HeaderBar(selectedTab = selectedTab, onClose = { closeGuard.requestClose(isDirty) })
                HorizontalDivider()
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    SideNav(
                        selected = selectedTab,
                        onSelected = { selectedTab = it },
                        modifier = Modifier
                            .fillMaxHeight()
                            .widthIn(min = 140.dp, max = 180.dp),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        TabContent(
                            selected = selectedTab,
                            draft = draft,
                            onDraftChange = { draft = it },
                        )
                    }
                }
                HorizontalDivider()
                FooterActions(
                    onCancel = { closeGuard.requestClose(isDirty) },
                    onSave = {
                        // Clear the previous error so a second successful
                        // attempt doesn\'t leave a stale banner behind.
                        saveError = onSave(draft)
                    },
                    errorMessage = saveError,
                )
            }
        }

        // 关闭确认：只在草稿真的改过时出现（见 SettingsCloseGuard）。三个出口：
        // 保存并关闭（成功才关）/ 放弃更改 / 继续编辑。
        if (closeGuard.confirmingClose) {
            AlertDialog(
                onDismissRequest = { closeGuard.keepEditing() },
                title = { Text(stringResource("settings_unsaved_title")) },
                text = { Text(stringResource("settings_unsaved_text")) },
                confirmButton = {
                    PressableButton(
                        onClick = closeGuard::discardChanges,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) { Text(stringResource("settings_unsaved_discard")) }
                },
                dismissButton = {
                    Row {
                        SecondaryButton(
                            onClick = closeGuard::keepEditing,
                        ) { Text(stringResource("settings_unsaved_keep")) }
                        Spacer(Modifier.width(8.dp))
                        PressableButton(
                            onClick = {
                                val error = onSave(draft)
                                if (error == null) {
                                    closeGuard.saveSucceeded()
                                } else {
                                    // 落盘失败：回设置界面展示原因，确认弹窗收起。
                                    closeGuard.saveFailed()
                                    saveError = error
                                }
                            },
                        ) { Text(stringResource("settings_unsaved_save")) }
                    }
                },
            )
        }
    }
}

// 整条标题栏是窗口拖拽区（同 NewConnectionDialog 的 HeaderBar）；✕ 按钮不受影响。
@Composable
private fun HeaderBar(selectedTab: SettingsTab, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .windowDragArea()
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${stringResource("settings_title")} - ${selectedTab.tabName}",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(
            onClick = onClose,
            standardMinWidth = false, // ✕ 是图标按钮，不套「4 字宽」标准。
        ) { Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun SideNav(
    selected: SettingsTab,
    onSelected: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 8.dp),
    ) {
        SettingsTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelected(tab) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = tab.tabName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TabContent(
    selected: SettingsTab,
    draft: Config,
    onDraftChange: (Config) -> Unit,
) {
    // Scroll inside the tab so longer content (especially AI tab with one card
    // per SPI provider) is reachable even on monitors shorter than the
    // dialog\'s max heightIn.
    val scrollState = rememberScrollState()
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(scrollState),
        ) {
            when (selected) {
                SettingsTab.GENERAL -> GeneralSettingsPane(
                    value = draft.general,
                    onChange = { onDraftChange(draft.copy(general = it)) },
                )
                SettingsTab.APPEARANCE -> AppearanceSettingsPane(
                    value = draft.theme,
                    onChange = { onDraftChange(draft.copy(theme = it)) },
                )
                SettingsTab.AI -> AiSettingsPane(
                    value = draft.ai,
                    onChange = { onDraftChange(draft.copy(ai = it)) },
                )
            }
        }
        // Scrollbar only appears when there\'s something to scroll (i.e. when
        // content height exceeds the visible area). It\'s pinned to the right
        // edge, 8 dp wide, the same convention the message list uses.
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(8.dp)
                .fillMaxHeight(),
        )
    }
}

/**
 * Save / Cancel row. When [errorMessage] is non-null the save failed and the
 * dialog is still open - show why, above the buttons and in the error role,
 * so the user isn\'t left with a silently-closed dialog and lost edits.
 */
@Composable
private fun FooterActions(
    onCancel: () -> Unit,
    onSave: () -> Unit,
    errorMessage: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryButton(
                onClick = onCancel,
            ) { Text(stringResource("common_cancel")) }
            Spacer(Modifier.width(8.dp))
            PressableButton(onClick = onSave) { Text(stringResource("common_save")) }
        }
    }
}
