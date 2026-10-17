plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.nucleus.core.runtime)
            implementation(libs.nucleus.window)
            implementation(libs.nucleus.application)
            implementation(libs.material.kolor)
            implementation(compose.material3)
            implementation(compose.components.resources)
            // Material Icons Extended：跨平台一致的矢量图标（Linux 无 Emoji 字体也能正常显示）。
            implementation(compose.materialIconsExtended)

            // Shared UI primitives (PressableButton, …) — both :app-ui and
            // :ai-panel consume these so they stay visually consistent.
            implementation(projects.uiCommon)

            // Right-side AI assistant panel.
            implementation(projects.aiUi)
            // AI 落库（ai_connection / chat_history）——bootstrapEvents 里注册订阅。
            implementation(projects.aiService)

            // OpenAI-compatible AI adapter module (:openai-service) — adapter, registry
            // and provider descriptors live there; the connection model is in app-core.
            implementation(projects.openaiService)
            // OpenAI branding/resources (logo shown when creating a connection).
            implementation(projects.openaiUi)

            // Anthropic AI adapter module — same shape as the OpenAI pair above; both
            // are discovered via SPI at runtime and register into AiAdapterRegistry.
            implementation(projects.anthropicService)
            implementation(projects.anthropicUi)

            // Business modules (discovered via SPI at runtime).
            implementation(projects.ibmMqService)
            implementation(projects.ibmMqUi)
            implementation(projects.kafkaService)
            implementation(projects.kafkaUi)
            implementation(projects.pulsarService)
            implementation(projects.pulsarUi)
            implementation(projects.rabbitmqService)
            implementation(projects.rabbitmqUi)
            implementation(projects.rocketmqService)
            implementation(projects.rocketmqUi)
            // 解释器注册表：Main.kt 启动 / 换语言时把解释器的私有 i18n 表合进全局表
            // （ibmMqUi/kafkaUi 的 implementation 依赖不传递，宿主自己声明一份）。
            implementation(projects.messageInterpreterRegistry)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            // FileKit：在 main() 里统一 FileKit.init(appId)，所有模块共用同一套文件选择器。
            implementation(libs.filekit.dialogs)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // ModuleI18nMergeTest 要解析各模块 i18n 表的原始 JSON 来算键交集
            // （模块表是全局合并的，重名会静默互相覆盖）。
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.mqclient.app.MainKt"
    }
}
