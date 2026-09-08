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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberDialogState
import com.mqclient.core.Config
import dev.nucleusframework.application.LocalNucleusWindow
import com.mqclient.uicommon.PressableButton
import dev.nucleusframework.application.DecoratedDialog

/** Tabs the side nav can select. Order matches visual order top → bottom. */
internal enum class SettingsTab(val title: String, val tabName: String) {
    GENERAL("\u2699\uFE0F \u901A\u7528", "\u901A\u7528"),
    APPEARANCE("\uD83C\uDFA8 \u5916\u89C2", "\u5916\u89C2"),
    AI("\uD83E\uDD16 AI", "AI"),
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

    // A real (Nucleus) window instead of the old fixed-size popup, so the user
    // can drag any edge or corner to resize it. The Surface fills the whole
    // window, so its content tracks whatever size is picked.
    DecoratedDialog(
        onCloseRequest = onDismiss,
        state = rememberDialogState(size = DpSize(760.dp, 600.dp)),
        title = "\u8BBE\u7F6E",
        resizable = true,
    ) {
        // Keep the window usable: don't let it shrink below what the side rail
        // + tab content need.
        LocalNucleusWindow.current.setMinimumSize(DpSize(720.dp, 480.dp))

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                HeaderBar(selectedTab = selectedTab, onClose = onDismiss)
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
                    onCancel = onDismiss,
                    onSave = {
                        // Clear the previous error so a second successful
                        // attempt doesn\'t leave a stale banner behind.
                        saveError = onSave(draft)
                    },
                    errorMessage = saveError,
                )
            }
        }
    }
}

@Composable
private fun HeaderBar(selectedTab: SettingsTab, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "\u8BBE\u7F6E - ${selectedTab.tabName}",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        PressableButton(
            onClick = onClose,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) { Text("\u2715") }
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
                Text(
                    text = tab.title,
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
            PressableButton(
                onClick = onCancel,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) { Text("\u53D6\u6D88") }
            Spacer(Modifier.width(8.dp))
            PressableButton(onClick = onSave) { Text("\u4FDD\u5B58") }
        }
    }
}
