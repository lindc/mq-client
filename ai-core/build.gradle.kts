plugins {
    id("mq.kotlin-jvm")
    id("mq.serialization")
}

/**
 * AI 核心层：适配器契约 + HTTP 基建 + 注册表。**不认识任何具体 provider**。
 *
 * 每个 provider 一个模块（openai-service / anthropic-service / …），它们依赖本模块
 * 拿契约，并在自己的 `ServiceAdapter.initialize` 里往 [com.mqclient.ai.AiAdapterRegistry]
 * 登记 —— 删掉某个 provider 就等于删一个模块，不动 AI 栈的其余部分。
 *
 * 包名沿用 `com.mqclient.ai`（契约的天然位置）；模块名区分「核心」与「provider 实现」。
 */
kotlin {
    sourceSets {
        commonMain.dependencies {
            // AiConnection（连接模型）在 app-core 的 commonMain，**出现在 AiAdapter 的
            // 公开签名里**（chat / chatStream / testConnection 的参数）—— 必须 api 暴露，
            // 否则 provider 模块拿不到这个类型。
            api(projects.appCore)
            // wire 模型 / JSON 解析用 kotlinx.serialization。
            implementation(libs.kotlinx.serialization.json)
            // 适配器层的 HTTP 客户端：core + CIO 引擎（多平台构件）。
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            // Java 引擎：ignoreSsl 连接的 trust-all 客户端（见 AiHttp）。
            implementation(libs.ktor.client.java)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
