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
            // wire 模型（请求 / 响应 DTO）与手工 JSON 解析用 kotlinx.serialization。
            implementation(libs.kotlinx.serialization.json)
            // 适配器层的 HTTP 客户端（Ktor：core + CIO 引擎，均为多平台构件）。
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
        }
        jvmTest.dependencies {
            // 适配器契约测试用 MockEngine 替换真实网络。
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
        }
    }
}
