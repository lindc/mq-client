package com.mqclient.uicommon.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mqclient.core.ModuleI18nTable
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
 * startup according to `config.toml`'s language. Business modules can ship
 * their own tables (`composeResources/files/i18n/<base>_<lang>.json`, base
 * declared via `AdapterUI.i18nTableBase`) which are merged into the same
 * runtime table — keys are owned by the module that declares them, so adding
 * module copy never touches the shared file. Access is a plain global
 * lookup — no CompositionLocal; live refresh comes from [revision] (a Compose
 * state bumped by [load]), so every composable that called [stringResource]
 * re-renders on the new table. The trigger is `core.language.changed` on the
 * event bus (see Main.kt).
 *
 * Behaviour:
 *  - [load] with "zh-CN" merges Chinese over the **English base table**, so a
 *    missing Chinese key gracefully falls back to English;
 *  - [load] with anything else (including null) uses the English table —
 *    the default language for a global release is English;
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
     * — including null — means English, the default for a global release).
     *
     * **English is always the base table**; Chinese only overlays when explicitly
     * selected. Missing-key fallback therefore ends in English text in every
     * language: 模块中文 → 模块英文 → 公共中文 → 公共英文 → key（zh 模式）；
     * 模块英文 → 公共英文 → key（en 模式）。key 本身只是 bundle 损坏的最后防线。
     *
     * [moduleTables] 是各业务模块的私有表（`AdapterUI.i18nTables`，由 ModuleRegistry
     * 从 SPI 收集；读取器由模块自己提供 —— compose 运行时资源按库包名隔离，宿主
     * 无法跨库按路径读）。每个表按语言取内容、与同语言的公共表合并（模块键覆盖
     * 公共键；base 全应用唯一，正常不会撞）。en 表作为底表要求与 zh 表键齐全
     * （构建校验：全部 `*_zh-CN.json` 与 `*_en-US.json` 键差集为空）。
     */
    suspend fun load(
        language: String?,
        moduleTables: List<ModuleI18nTable> = emptyList(),
    ) {
        val wantZh = language.equals(LANGUAGE_ZH_CN, ignoreCase = true)
        val en = parse(readOrNull(STRINGS_EN_PATH)) + moduleTableContent(moduleTables, LANGUAGE_EN_US)
        val zh =
            if (wantZh) {
                parse(readOrNull(STRINGS_ZH_PATH)) + moduleTableContent(moduleTables, LANGUAGE_ZH_CN)
            } else {
                emptyMap()
            }
        table = if (wantZh) en + zh else en
        loadedLanguage = if (wantZh) LANGUAGE_ZH_CN else LANGUAGE_EN_US
        // 通知所有已订阅的组合（见 revision 的注释）。
        revision++
    }

    /**
     * 合并所有模块表在 [language] 语言下的内容。单个表读取失败只跳过它自己
     * （记日志，键回落到公共表 / key 本身），不让一个坏模块拖垮整张表。
     */
    private suspend fun moduleTableContent(
        tables: List<ModuleI18nTable>,
        language: String,
    ): Map<String, String> = tables.fold(emptyMap()) { acc, table ->
        val text = runCatching { table.reader(language) }
            .onFailure { Log.error(LOG_TAG, "failed to read module table '${table.base}': $it") }
            .getOrNull()
        acc + parse(text)
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
