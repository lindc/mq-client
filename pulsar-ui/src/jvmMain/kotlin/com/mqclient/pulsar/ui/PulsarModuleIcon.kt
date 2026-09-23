package com.mqclient.pulsar.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import com.mqclient.core.ModuleIcon
import mq_client.pulsar_ui.generated.resources.Res
import mq_client.pulsar_ui.generated.resources.ic_pulsar
import org.jetbrains.compose.resources.painterResource

/** 本模块的品牌图标：drawable 与渲染函数都归 ui 模块自己（见 [ModuleIcon]）。 */
class PulsarModuleIcon : ModuleIcon {
    override val serviceId = "pulsar"

    @Composable
    override fun painter(): Painter = painterResource(Res.drawable.ic_pulsar)
}
