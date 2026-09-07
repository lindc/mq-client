package com.mqclient.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import mq_client.app_ui.generated.resources.Res
import mq_client.app_ui.generated.resources.ic_artemis
import mq_client.app_ui.generated.resources.ic_ibm_mq
import mq_client.app_ui.generated.resources.ic_kafka
import mq_client.app_ui.generated.resources.ic_mq
import mq_client.app_ui.generated.resources.ic_rabbitmq
import mq_client.app_ui.generated.resources.ic_rocketmq
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Middleware artwork lives here rather than in the `*-service` modules: those
 * are plain Kotlin modules without the Compose plugin, so they cannot carry
 * `composeResources`. Keyed by [com.mqclient.core.BusinessService.id].
 */
private val middlewareIcons: Map<String, DrawableResource> =
    mapOf(
        "rabbitmq" to Res.drawable.ic_rabbitmq,
        "kafka" to Res.drawable.ic_kafka,
        "ibm-mq" to Res.drawable.ic_ibm_mq,
        "artemis" to Res.drawable.ic_artemis,
        "rocketmq" to Res.drawable.ic_rocketmq,
    )

@Composable
fun middlewareIcon(serviceId: String): Painter =
    painterResource(middlewareIcons[serviceId] ?: Res.drawable.ic_mq)
