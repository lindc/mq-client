package com.mqclient.core

import androidx.compose.runtime.Composable

/**
 * UI contribution of a business module, discovered automatically via Java SPI.
 * Each `*-ui` module provides one implementation and registers it under
 * `META-INF/services/com.mqclient.core.AdapterUI`.
 *
 * [serviceId] must match the [ServiceAdapter.id] of the related service module
 * so the UI can be attached to its metadata.
 */
interface AdapterUI {
    /** Must match the related [ServiceAdapter.id]. */
    val serviceId: String

    /** Title shown for this module's content. */
    val title: String

    /**
     * 本模块**私有的** i18n 字符串表；默认空 = 模块没有自己的字符串表（只用公共表）。
     *
     * 约定：模块在自己的 `composeResources/files/i18n/<base>_<lang>.json` 里放字符串
     * （如 `ibm_mq_zh-CN.json` / `ibm_mq_en-US.json`），`LanguageResources.load` 会把
     * 各模块表与公共表合并进同一张运行时表 —— `stringResource(key)` 的用法完全不变，
     * 只是键的**所有权**归模块：模块加文案（如导航项 `nav_*`）不需要动 ui-common 的
     * 公共文件。
     *
     * 读取必须由模块自己做（用模块自己生成的 `Res`）：Compose 的运行时资源按库的
     * 包名隔离，宿主拿路径跨库读不到 —— 见 [ModuleI18nTable]。
     */
    val i18nTables: List<ModuleI18nTable> get() = emptyList()

    /** The composable content rendered when this module is selected. */
    val content: @Composable () -> Unit

    /**
     * 按导航项（`NavigationItem.id`，即 Tab 的 objectType）贡献的 Tab 内容；
     * 返回 null（默认）= 宿主回落「coming soon」占位页。
     *
     * 每次进入 Tab 都会重新调用 —— 模块应返回轻量的组合 lambda，状态留在
     * `remember` / ViewModel 层（按 [connectionId] 记）。
     */
    fun contentFor(objectType: String, connectionId: String): (@Composable () -> Unit)? = null
}
