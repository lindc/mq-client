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
            // app-core 的统一日志入口（Log）：失败上报走它，不再自己摸 System.err。
            implementation(projects.appCore)
            // nucleus 应用框架：ObjectDetailDialog 用其 DecoratedDialog
            // （内部把 AWT 窗口操作包到 EDT，规避 Tao 主线程非 EDT 的崩溃）。
            implementation(libs.nucleus.application)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // ✕ 关闭按钮等图标（ObjectDetailDialog 标题栏）。
            implementation(compose.materialIconsExtended)
            // nucleus 窗口原语：ObjectDetailDialog 的 windowDragArea / setMinimumSize。
            implementation(libs.nucleus.window)
            // Compose resources packaging (i18n JSON tables under
            // composeResources/files/i18n/).
            implementation(compose.components.resources)
            // JSON parsing for LanguageResources (runtime only — no
            // @Serializable classes, so no serialization compiler plugin).
            implementation(libs.kotlinx.serialization.json)
        }
        // jvmTest: kotlin.test + JUnit4 runner for the i18n behaviour tests.
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}