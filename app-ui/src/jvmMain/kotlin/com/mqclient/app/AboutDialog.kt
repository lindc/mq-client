package com.mqclient.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mqclient.core.CoreModule
import com.mqclient.core.ModuleRegistry
import com.mqclient.uicommon.PressableButton
import com.mqclient.uicommon.i18n.stringResource
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import mq_client.app_ui.generated.resources.Res
import mq_client.app_ui.generated.resources.logo
import org.jetbrains.compose.resources.painterResource

/**
 * Custom About dialog laid out like a product "about" card:
 *   - header band with the title and a close (✕) button on the right
 *   - a two-column body: the ticking clock (logo) on the left, app info +
 *     the supported-middleware list + action buttons on the right
 *   - a footer with the copyright line
 */
@Composable
internal fun AboutDialog(onDismiss: () -> Unit) {
    var showLicense by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Root box so the license dialog can overlay the card.
        Box {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.size(620.dp, 580.dp),
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Header band: title on the left, close button on the right.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource("about_title"),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    // Body: clock column + info column.
                    Row(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                        // Left column: the analog clock (logo) with a "时钟" caption.
                        Column(
                            modifier = Modifier.width(120.dp).fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            AnalogClock(Modifier.size(96.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(stringResource("titlebar_clock"), style = MaterialTheme.typography.labelMedium)
                        }

                        Spacer(Modifier.width(16.dp))

                        // Right column: app info, supported middleware, actions.
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            // App icon on the left, 4 text lines on the right.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(
                                    painter = painterResource(Res.drawable.logo),
                                    contentDescription = stringResource("about_logo_description"),
                                    modifier = Modifier.size(72.dp),
                                )
                                Spacer(Modifier.width(16.dp))
                                Column {
                                    Text("MQClient", style = MaterialTheme.typography.headlineSmall)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        stringResource("about_version"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(stringResource("about_description_line1"), style = MaterialTheme.typography.bodyMedium)
                                    Text(stringResource("about_description_line2"), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            Text(stringResource("about_supported_middleware"), style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(8.dp))
                            // Bounded, scrollable area: the scrollbar only shows up
                            // when the list outgrows the max height (otherwise hidden).
                            Box(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                            ) {
                                val listScroll = rememberScrollState()
                                Column(
                                    modifier =
                                        Modifier
                                            .fillMaxSize()
                                            .padding(end = 10.dp)
                                            .verticalScroll(listScroll),
                                ) {
                                    aboutMiddleware.forEach { lib ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(lib.name, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                                            VerticalDivider(
                                                modifier =
                                                    Modifier
                                                        .height(20.dp)
                                                        .padding(horizontal = 12.dp),
                                            )
                                            Text(
                                                lib.protocol,
                                                modifier = Modifier.weight(1f),
                                                textAlign = TextAlign.End,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                VerticalScrollbar(
                                    adapter = rememberScrollbarAdapter(listScroll),
                                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(12.dp))

                            // Action buttons: license viewer + close, equal width.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                PressableButton(
                                    onClick = { showLicense = true },
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource("license_view")) }
                                Spacer(Modifier.width(12.dp))
                                PressableButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource("common_close")) }
                            }
                        }
                    }

                    // Footer with the copyright line.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            "© 2026 Your Name. All rights reserved.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (showLicense) {
                LicenseDialog(onDismiss = { showLicense = false })
            }
        }
    }
}

/**
 * Middleware / protocols listed in the About dialog.
 *
 * Sourced from [ModuleRegistry] (Java SPI) instead of being hardcoded here, so
 * adding a new `<mq>-service` module — together with its `protocol` field —
 * shows up in the About dialog automatically. Sorted by display name so the
 * ordering is stable across runs (SPI discovery order is not guaranteed).
 */
private val aboutMiddleware: List<Library> by lazy {
    ModuleRegistry.services
        .sortedBy { it.name }
        .map { Library(it.name, it.protocol) }
}

/** Simple dialog listing the open-source components this app bundles. */
@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    // Load the third-party list lazily from the packaged resource. The file is
    // bundled under composeResources/files/ so the Compose Resources Gradle
    // plugin picks it up; each line is "name,license,copyright".
    val components by produceState<List<ThirdPartyComponent>>(initialValue = emptyList()) {
        value = loadThirdPartyComponents()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { PressableButton(onClick = onDismiss) { Text(stringResource("common_close")) } },
        title = { Text(stringResource("license_title")) },
        text = { LibraryList(components, Modifier.size(380.dp, 300.dp)) },
    )
}

/** Path to the bundled third-party-component manifest inside composeResources. */
private const val ThirdPartyComponentPath = "files/third-party-component/third-party-component.txt"

/**
 * Parses the third-party-component manifest into [ThirdPartyComponent] rows.
 *
 * Uses an RFC 4180 subset: a field wrapped in double quotes may contain
 * commas and newlines; a literal `"` inside a quoted field is escaped as `""`.
 * Records that don't split into the three expected fields are skipped so a
 * stray blank line or a malformed entry never crashes the dialog.
 */
private suspend fun loadThirdPartyComponents(): List<ThirdPartyComponent> =
    try {
        parseComponents(Res.readBytes(ThirdPartyComponentPath).decodeToString())
    } catch (error: Exception) {
        // 读不出来 = 对话框空白，用户只会当成程序坏了 —— 报到错误通道上去。
        CoreModule.reportError(
            message = "第三方组件清单读取失败，许可证列表暂时为空",
            source = "app-ui",
            error = error,
        )
        emptyList()
    }

/** Classic three-hand clock that ticks every second off the system clock. */
@Composable
private fun AnalogClock(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = LocalTime.now()
        }
    }
    // Capture theme colours outside the Canvas: the draw lambda has no Composer,
    // so it cannot read CompositionLocals like MaterialTheme.colorScheme.
    val outline = MaterialTheme.colorScheme.outline
    val onSurface = MaterialTheme.colorScheme.onSurface
    val error = MaterialTheme.colorScheme.error
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f

        // Point at `angleDeg` clockwise from 12 o'clock, `dist` from centre.
        fun point(angleDeg: Float, dist: Float): Offset {
            val rad = angleDeg * Math.PI.toFloat() / 180f
            return Offset(cx + sin(rad) * dist, cy - cos(rad) * dist)
        }

        drawCircle(color = outline, radius = r, style = Stroke(1.5.dp.toPx()))

        // Hour ticks; longer at the quarters.
        val inner = r - 4.dp.toPx()
        for (i in 0 until 12) {
            val len = if (i % 3 == 0) 12.dp.toPx() else 6.dp.toPx()
            val a = i * 30f
            drawLine(
                color = outline,
                start = point(a, inner),
                end = point(a, inner + len),
                strokeWidth = if (i % 3 == 0) 2.dp.toPx() else 1.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        val sec = now.second + now.nano / 1_000_000_000f
        val min = now.minute + sec / 60f
        val hour = (now.hour % 12) + min / 60f

        drawLine(onSurface, Offset(cx, cy), point(hour * 30f, r * 0.5f), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(onSurface, Offset(cx, cy), point(min * 6f, r * 0.75f), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawLine(error, Offset(cx, cy), point(sec * 6f, r * 0.85f), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(onSurface, radius = 3.dp.toPx(), center = Offset(cx, cy))
    }
}

/** One row in the "支持的中间件" list inside the About dialog. */
private data class Library(
    val name: String,
    val protocol: String,
)

/**
 * Library list with alternating row backgrounds. Each component occupies a
 * 3-line block (name / copyright / license) so the dialog reads as the
 * attribution card the mockup calls for. The scrollbar only shows up when the
 * list outgrows the body (Scrollbar hides itself otherwise).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryList(components: List<ThirdPartyComponent>, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            components.forEachIndexed { index, lib ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(if (index % 2 == 0) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = lib.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = lib.copyright,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "License: ${lib.license}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scrollState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}
