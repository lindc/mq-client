package com.mqclient.uicommon.i18n

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mq_client.ui_common.generated.resources.Res

/**
 * Minimal i18n infrastructure — step 1 only (infrastructure, no live refresh).
 *
 * String tables live in bundled JSON resources
 * (`composeResources/files/i18n/strings_<lang>.json`) and are loaded once at
 * startup according to `config.json`'s language. Access is a plain global
 * lookup — no CompositionLocal, no reactive refresh; wiring text into the UI
 * and reacting to language changes is the follow-up (message-bus) step.
 *
 * Behaviour:
 *  - [load] with "en-US" merges English over the Chinese base table, so a
 *    missing English key gracefully falls back to Chinese;
 *  - [load] with anything else (including null) uses the Chinese table;
 *  - a failed resource read degrades to an empty table and [stringResource]
 *    returns the key itself, so the UI never crashes on a missing bundle.
 *
 * The table reference is swapped atomically (@Volatile), which keeps this
 * safe for the future bus-driven reload: readers either see the old or the
 * new table, never a partial one.
 */
object LanguageResources {

    /** Language codes used as the file suffix of the string tables. */
    const val LANGUAGE_ZH_CN: String = "zh-CN"
    const val LANGUAGE_EN_US: String = "en-US"

    private const val STRINGS_ZH_PATH = "files/i18n/strings_zh-CN.json"
    private const val STRINGS_EN_PATH = "files/i18n/strings_en-US.json"

    private val json = Json { ignoreUnknownKeys = true }

    /** Currently active table; swapped as a whole by [load]. */
    @Volatile
    private var table: Map<String, String> = emptyMap()

    /** Code of the table [load] was last called with (null = never loaded). */
    @Volatile
    private var loadedLanguage: String? = null

    /**
     * Loads the string table for [language] ("zh-CN" / "en-US"; anything else
     * — including null — means Chinese). Chinese is always parsed first and
     * acts as the base; English only overrides keys it actually provides.
     */
    suspend fun load(language: String?) {
        val zh = parse(readOrNull(STRINGS_ZH_PATH))
        val wantEn = language.equals(LANGUAGE_EN_US, ignoreCase = true)
        val en = if (wantEn) parse(readOrNull(STRINGS_EN_PATH)) else emptyMap()
        table = if (wantEn) zh + en else zh
        loadedLanguage = if (wantEn) LANGUAGE_EN_US else LANGUAGE_ZH_CN
    }

    /** Code of the currently loaded table, or null before the first [load]. */
    fun currentLanguage(): String? = loadedLanguage

    /** Returns the text for [key], or the key itself when the table is missing it. */
    fun stringResource(key: String): String = table[key] ?: key

    /* ------------------------------ internals ------------------------------ */

    private suspend fun readOrNull(path: String): String? = try {
        Res.readBytes(path).decodeToString()
    } catch (t: Throwable) {
        System.err.println("[LanguageResources] failed to read $path: $t")
        null
    }

    /** Parses a flat `{"key": "value"}` table; malformed input yields an empty map. */
    private fun parse(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        return try {
            json.parseToJsonElement(text)
                .jsonObject
                .mapValues { (_, value) -> value.jsonPrimitive.content }
        } catch (t: Throwable) {
            System.err.println("[LanguageResources] failed to parse string table: $t")
            emptyMap()
        }
    }
}

/** Convenience global accessor, mirroring the composable-resources API shape. */
fun stringResource(key: String): String = LanguageResources.stringResource(key)
