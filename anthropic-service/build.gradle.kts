plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            // AiConnection（配置模型）在 commonMain 里被适配器接口引用。
            implementation(projects.appCore)
            // AI 适配器接口 / IR / 注册表 / HTTP 基建都在 openai-service（com.mqclient.ai）。
            implementation(projects.openaiService)
            // wire 模型（请求 / 响应 DTO）用 kotlinx.serialization。
            implementation(libs.kotlinx.serialization.json)
            // 适配器层的 HTTP 客户端 API（客户端实例复用 openai-service 的 AiHttp）。
            implementation(libs.ktor.client.core)
        }
        jvmTest.dependencies {
            // 适配器契约测试用 MockEngine 替换真实网络。
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
        }
    }
}
