package com.mqclient.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

/**
 * 业务模块图标的 SPI（与 [AdapterUI] 平行但更轻）：每个 `*-ui` 模块把自己品牌
 * 图标的 drawable 与 [painter] 一起带在**自己模块里**，注册在
 * `META-INF/services/com.mqclient.core.ModuleIcon`。
 *
 * 为什么不集中在 app-ui 的一张 map 里：图标素材与渲染函数都属于模块的品牌资产，
 * 谁的图标归谁的 `*-ui` 模块管（openai-ui 的 OpenAiBranding 先例）；app-ui 只按
 * [serviceId] 查询，新模块注册 SPI 即生效，不需要改任何集中清单。
 *
 * 服务模块没有 Compose 插件、带不了 composeResources，所以这个 SPI 由 ui 模块
 * 实现 —— service 没有 ui 模块时就没有图标（调用方自行 fallback）。
 *
 * `Painter` 需要 ui-graphics：app-core 仅为此引 compose ui-graphics，仍不引
 * foundation / material（见 app-core/build.gradle.kts 的注释）。
 */
interface ModuleIcon {

    /** Must match the related [ServiceAdapter.id]. */
    val serviceId: String

    /** 图标的 [Painter]；尺寸由调用方约束（`Modifier.size(...)`），实现只管画什么。 */
    @Composable
    fun painter(): Painter
}
