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
     * 只是键的**所有权**归模块：模块加文案不需要动 ui-common 的公共文件。
     *
     * **键名约定**（模块表是全局合并的，重名会静默互相覆盖 —— 合并顺序取 SPI 的
     * classpath 顺序，哪个赢都不一定；守卫见 app-ui 的 `ModuleI18nMergeTest`）：
     *  - 模块的键一律带**模块前缀**（`kafka_ui_*` / `ibm_mq_*` / `rocketmq_ui_*`），
     *    **导航项也不例外**（`ibm_mq_nav_queues` / `kafka_ui_nav_topics`）—— 模块自己定
     *    文案、自己管表，新增模块不需要动公共表。`NavigationItem.titleKey` 填前缀后的
     *    键即可，宿主只按 titleKey 查表、不关心键名；
     *  - 只有**真正跨模块的通用词**才放公共表（`common_*` / `menu_*` / `settings_*` …），
     *    模块不要定义它们；
     *  - `ibm_mq_attrs_*` 这类属性名表的键是 `MQXXX.title`，常量名自带命名空间 ——
     *    **千万不要加模块前缀**，运行时按常量名拼（`valueConstMap` → `<CONST>.title`），
     *    加了就再也查不到。
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
