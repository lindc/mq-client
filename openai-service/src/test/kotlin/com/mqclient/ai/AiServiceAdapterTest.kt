package com.mqclient.ai

import com.mqclient.core.event.EventBus
import com.mqclient.core.scheduler.JobScheduler
import com.mqclient.core.spi.ServiceContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 注册时机契约：OpenAI 兼容 provider 由**本模块**在 `initialize` 里登记。
 *
 * 这条在拆分前是隐式的（`AiAdapterRegistry.init` 内置登记），拆分后变成模块自己的
 * 责任 —— 一旦 initialize 忘了登记，设置页的 Provider 下拉会静默变空、已有连接
 * 发消息会报「找不到适配器」。所以这里直接跑一遍 initialize 并断言注册表可见。
 */
class AiServiceAdapterTest {

    @Test
    fun `initialize registers the openai-compatible provider`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val context = ServiceContext.of(
                moduleId = "openai-compatible",
                eventBus = EventBus(scope),
                parentScope = scope,
                jobScheduler = JobScheduler(scope, EventBus(scope)),
            )

            AiServiceAdapter().initialize(context)

            assertNotNull(
                AiAdapterRegistry.provider("openai-compatible"),
                "initialize 必须把 provider 预置登记进注册表",
            )
            assertEquals(
                "openai-compatible",
                AiAdapterRegistry.adapter("openai-compatible")?.providerId,
                "initialize 必须同时登记适配器（否则仅下拉有项、发送仍失败）",
            )
        } finally {
            scope.cancel()
        }
    }
}
