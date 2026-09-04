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
            implementation(libs.compose.macos.ui)
            implementation(libs.material.kolor)
            implementation(libs.compose.icons.fontawesome)
            implementation(libs.compose.icons.simple)
            implementation(libs.compose.icons.tabler)

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
