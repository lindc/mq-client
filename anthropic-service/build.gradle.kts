plugins {
    id("mq.jvm-serialization")
}

dependencies {
    // AiConnection（配置模型）在 commonMain 里被适配器接口引用。
    implementation(projects.appCore)
    // AI 适配器契约 / IR / 注册表 / HTTP 基建都在 ai-core（com.mqclient.ai）。
    // 不再依赖 openai-service —— 两个 provider 模块平级。
    api(projects.aiCore)
    // wire 模型（请求 / 响应 DTO）用 kotlinx.serialization。
    implementation(libs.kotlinx.serialization.json)
    // 适配器层的 HTTP 客户端 API（客户端实例复用 ai-core 的 AiHttp）。
    implementation(libs.ktor.client.core)
    // JUnit assumption 包装：LiveTest 无环境时记 SKIP 而不是 PASSED。
    testImplementation(projects.testSupport)
    // 适配器契约测试用 MockEngine 替换真实网络。
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
}
