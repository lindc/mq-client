package com.mqclient.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.mqclient.core.ModuleRegistry
import mq_client.app_ui.generated.resources.Res
import mq_client.app_ui.generated.resources.ic_mq
import org.jetbrains.compose.resources.painterResource

/**
 * 按模块 id 取品牌图标。图标资产**归各 `*-ui` 模块自己**（drawable + 渲染函数 +
 * SPI 注册，见 [com.mqclient.core.ModuleIcon]），这里只按 [ServiceAdapter.id] 语义
 * 的 serviceId 查询；没注册图标的模块（没有 ui 模块的 service）落到通用 `ic_mq`。
 *
 * 之前图标集中在一张硬编码 map 里，新模块要手动加条目才不显示 fallback；SPI 化
 * 之后新 ui 模块注册即生效，app-ui 不再需要知道任何具体模块。
 */
@Composable
fun middlewareIcon(serviceId: String): Painter {
    val fromModule = ModuleRegistry.icons.firstOrNull { it.serviceId == serviceId }
    if (fromModule != null) return fromModule.painter()
    return painterResource(Res.drawable.ic_mq)
}
