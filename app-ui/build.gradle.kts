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

            // Shared UI primitives (PressableButton, …) — both :app-ui and
            // :ai-panel consume these so they stay visually consistent.
            implementation(projects.uiCommon)

            // Right-side AI assistant panel.
            implementation(projects.aiPanel)

            // AI providers — each is an independent module registered through
            // the AiProvider SPI, so removing one is just deleting this line.
            implementation(projects.aiClaudeService)
            implementation(projects.aiOpenaiService)
            implementation(projects.aiOllamaService)

            // Business modules (discovered via SPI at runtime).
            implementation(projects.ibmMqService)
            implementation(projects.ibmMqUi)
            implementation(projects.kafkaService)
            implementation(projects.kafkaUi)
            implementation(projects.artemisService)
            implementation(projects.artemisUi)
            implementation(projects.rabbitmqService)
            implementation(projects.rabbitmqUi)
            implementation(projects.rocketmqService)
            implementation(projects.rocketmqUi)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.mqclient.app.MainKt"
    }
}
