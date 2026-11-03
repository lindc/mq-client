plugins {
    id("mq.kotlin-jvm")
    id("mq.serialization")
}

/**
 * OpenAI 兼容 provider：只含 [com.mqclient.ai.adapter.OpenAiCompatibleAdapter] 与
 * provider 预置（`AiProviders.OPENAI_COMPATIBLE`）。
 *
 * 契约、HTTP 基建、注册表都在 `:ai-core` —— 本模块只是「一个 provider 实现」，
 * 与 `:anthropic-service` 平级。想删掉 OpenAI 支持：删本模块 + 从 app-ui 的依赖里
 * 去掉即可，AI 栈其余部分不受影响。
 */
kotlin {
    sourceSets {
        commonMain.dependencies {
            // api：本模块的消费方（openai-ui / app-ui）同时要用到 ai-core 的
            // AiAdapter / AiProvider / 注册表，避免每个消费方重复声明。
            api(projects.aiCore)
            // OpenAiCompatibleAdapter 的 wire 模型与 JSON 处理。
            implementation(libs.kotlinx.serialization.json)
            // 适配器层 HTTP 客户端 API（客户端实例来自 ai-core 的 AiHttp）。
            implementation(libs.ktor.client.core)
        }
        jvmTest.dependencies {
            // 适配器契约测试用 MockEngine 替换真实网络。
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
        }
    }
}
