plugins {
    // 应用 Kotlin Multiplatform 插件（用于桌面 JVM 目标）
    alias(libs.plugins.kotlinMultiplatform)
    // 应用 Compose Multiplatform 插件（支持桌面）
    alias(libs.plugins.composeMultiplatform)
    // 应用 Compose 编译器插件（用于 @Composable 优化）
    alias(libs.plugins.composeCompiler)
    // 如果不需要 Kotlin/JVM 单独插件，可省略，因为 kotlinMultiplatform 已包含 JVM 支持
}

kotlin {
    // 仅配置桌面 JVM 目标
    jvm("desktop")  // 目标名称可自定义，例如 "desktop"

    sourceSets {
        // 通用代码（桌面与 JVM 共用）
        commonMain.dependencies {
            // Compose 基础（自动引入 UI、runtime、foundation 等）
            implementation("org.jetbrains.compose.runtime:runtime:1.12.0")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.0")
            implementation("org.jetbrains.compose.ui:ui:1.12.0")
            implementation("org.jetbrains.compose.components:components-resources:1.12.0")
            implementation("androidx.compose.material3:material3:1.4.0")

            // 添加 Material Kolor（注意别名）
            implementation(libs.material.kolor)
        }

        // 桌面特定代码（如果有平台特定依赖）
        val desktopMain by getting {
            dependencies {
                // 可添加桌面特定依赖，如 Swing 互操作等
            }
        }
    }
}