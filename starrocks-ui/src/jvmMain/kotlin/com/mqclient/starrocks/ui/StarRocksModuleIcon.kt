package com.mqclient.starrocks.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.mqclient.core.ModuleIcon
import mq_client.starrocks_ui.generated.resources.Res
import mq_client.starrocks_ui.generated.resources.ic_starrocks
import org.jetbrains.compose.resources.painterResource

/**
 * StarRocks 品牌图标：drawable 与渲染函数都归本 ui 模块自己（见 [ModuleIcon]）。
 *
 * 资产在 `src/commonMain/composeResources/drawable/ic_starrocks.png`，经 Compose
 * Resources 生成的 [Res] 加载 —— 与 KafkaModuleIcon / RocketMqModuleIcon 同构。
 * 注册在 `META-INF/services/com.mqclient.core.ModuleIcon`，宿主
 * `middlewareIcon("starrocks")` 按此 SPI 查询，左侧树/卡片即显示本图标；
 * 未注册时宿主回落通用 `ic_mq`（空心圆观感的来源）。
 */
class StarRocksModuleIcon : ModuleIcon {
    override val serviceId = "starrocks"

    @Composable
    override fun painter(): Painter = painterResource(Res.drawable.ic_starrocks)
}
