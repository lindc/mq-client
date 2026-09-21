package com.mqclient.app.fonts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font as PlatformFont
import com.mqclient.core.FONT_LXGW_WEN_KAI_MONO
import com.mqclient.core.FONT_SYSTEM_MONO
import com.mqclient.core.FONT_SYSTEM_SANS
import com.mqclient.core.FONT_SYSTEM_SERIF
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mq_client.app_ui.generated.resources.Res
import mq_client.app_ui.generated.resources.lxgw_wen_kai_mono_regular
import org.jetbrains.compose.resources.Font as ResourceFont

/**
 * Resolves a [com.mqclient.core.ThemeConfig.fontFamily] id into the renderable
 * [FontFamily] the app typography (and the settings preview) is built on.
 *
 * Three id shapes (see the constants in ConfigModel.kt):
 * 1. The bundled font id — the resource face, resolvable synchronously.
 * 2. The three legacy generic ids (`FONT_SYSTEM_*`) — mapped to Compose's
 *    generic family constants; Skia resolves them against system fonts. They
 *    no longer appear in the settings dropdown, they only survive in old
 *    config files.
 * 3. A system font family name — resolved to a disk file by [SystemFonts]
 *    (asynchronous: the first call triggers a full font-directory scan, too
 *    slow for composition) and loaded via the platform `Font(File)`.
 *    Unknown/uninstalled families and unloadable files fall back to
 *    [FontFamily.Default].
 *
 * While a system font is being resolved (or when it falls back) the caller
 * renders with [FontFamily.Default] for a frame or two — acceptable for an
 * infrequent settings change.
 */
@Composable
fun rememberAppFontFamily(fontId: String): FontFamily =
    when (fontId) {
        // ResourceFont is @Composable (it reads async resources), so it can't
        // sit inside remember's @DisallowComposableCalls lambda. FontFamily is
        // a thin wrapper — recreating it per composition is cheap.
        FONT_LXGW_WEN_KAI_MONO ->
            FontFamily(ResourceFont(Res.font.lxgw_wen_kai_mono_regular, FontWeight.Normal))

        FONT_SYSTEM_SANS -> FontFamily.SansSerif
        FONT_SYSTEM_SERIF -> FontFamily.Serif
        FONT_SYSTEM_MONO -> FontFamily.Monospace

        else -> {
            // 局部 val 而非 by 委托：delegated property 不能 smart cast，null 检查
            // 之后下面 PlatformFont(loaded) 就能安全解引用。
            val loaded = produceState<File?>(initialValue = null, fontId) {
                value = withContext(Dispatchers.IO) { SystemFonts.fileForFamily(fontId) }
            }.value
            // Font(File) reads the (potentially multi-MB) file eagerly, so it
            // stays inside remember; a corrupt/unreadable file degrades to the
            // default family instead of crashing composition.
            remember(fontId, loaded) {
                when {
                    loaded == null -> FontFamily.Default
                    else -> runCatching { FontFamily(PlatformFont(loaded)) }
                        .getOrDefault(FontFamily.Default)
                }
            }
        }
    }
