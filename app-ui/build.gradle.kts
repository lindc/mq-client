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

            // Business modules (discovered via SPI at runtime).
            implementation(projects.ibmMqService)
            implementation(projects.ibmMqUi)
            implementation(projects.kafkaService)
            implementation(projects.kafkaUi)
            implementation(projects.pulsarService)
            implementation(projects.pulsarUi)
            implementation(projects.artemisService)
            implementation(projects.artemisUi)
            implementation(projects.rabbitmqService)
            implementation(projects.rabbitmqUi)
            implementation(projects.rocketmqService)
            implementation(projects.rocketmqUi)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            // FileKit：在 main() 里统一 FileKit.init(appId)，所有模块共用同一套文件选择器。
            implementation(libs.filekit.dialogs)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.mqclient.app.MainKt"
    }
}
