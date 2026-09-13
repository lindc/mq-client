package com.mqclient.uicommon.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.log.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mq_client.ui_common.generated.resources.Res

/**
 * Minimal i18n infrastructure — infrastructure + live refresh.
 *
 * String tables live in bundled JSON resources
 * (`composeResources/files/i18n/strings_<lang>.json`) and are loaded at
 * startup according to `config.json`'s language. Access is a plain global
 * lookup — no CompositionLocal; live refresh comes from [revision] (a Compose
 * state bumped by [load]), so every composable that called [stringResource]
 * re-renders on the new table. The trigger is `core.language.changed` on the
 * event bus (see Main.kt).
 *
 * Behaviour:
 *  - [load] with "en-US" merges English over the Chinese base table, so a
 *    missing English key gracefully falls back to Chinese;
 *  - [load] with anything else (including null) uses the Chinese table;
 *  - a failed resource read degrades to an empty table and [stringResource]
 *    returns the key itself, so the UI never crashes on a missing bundle.
 *
 * The table reference is swapped atomically (@Volatile), which keeps this
 * safe for the bus-driven reload: readers either see the old or the
 * new table, never a partial one.
 */
object LanguageResources {

    /** Language codes used as the file suffix of the string tables. */
    const val LANGUAGE_ZH_CN: String = "zh-CN"
    const val LANGUAGE_EN_US: String = "en-US"

    private const val STRINGS_ZH_PATH = "files/i18n/strings_zh-CN.json"
    private const val STRINGS_EN_PATH = "files/i18n/strings_en-US.json"

    /** 统一日志 tag。 */
    private const val LOG_TAG = "language-resources"

    private val json = Json { ignoreUnknownKeys = true }

    /** Currently active table; swapped as a whole by [load]. */
    @Volatile
    private var table: Map<String, String> = emptyMap()

    /**
     * 表版本号，每次 [load] 递增。
     *
     * 唯一的用途是让 [stringResource] 变成「组合期可订阅」的读取：读一下它，
     * 读取方就能在换表时自动重组 —— 不需要 CompositionLocal，也不需要让整棵界面树
     * 换 key（后者会把用户的 UI 状态一起清掉）。这就是本文档里说的「由消息总线驱动的
     * 重载」，触发源是 app-core 事件总线的 `core.language.changed`。
     */
    private var revision by mutableStateOf(0)

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
        // 通知所有已订阅的组合（见 revision 的注释）。
        revision++
    }

    /** Code of the currently loaded table, or null before the first [load]. */
    fun currentLanguage(): String? = loadedLanguage

    /** Returns the text for [key], or the key itself when the table is missing it. */
    @Suppress("UNUSED_EXPRESSION")
    fun stringResource(key: String): String {
        // 组合期读一下版本号 = 声明「本界面依赖语言表」，换表时自动重组。
        revision
        return table[key] ?: key
    }

    /* ------------------------------ internals ------------------------------ */

    private suspend fun readOrNull(path: String): String? = try {
        Res.readBytes(path).decodeToString()
    } catch (t: Throwable) {
        Log.error(LOG_TAG, "failed to read $path: $t")
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
            Log.error(LOG_TAG, "failed to parse string table: $t")
            emptyMap()
        }
    }
}

/** Convenience global accessor, mirroring the composable-resources API shape. */
fun stringResource(key: String): String = LanguageResources.stringResource(key)
