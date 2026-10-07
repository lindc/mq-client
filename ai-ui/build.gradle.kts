plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
            // 面板历史 / 审计记录的读写（chat_history 表归 ai-service 管）。
            implementation(projects.aiService)
            implementation(projects.openaiService)
            implementation(projects.uiCommon)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // Material Icons Extended：跨平台一致的矢量图标（Linux 无 Emoji 字体也能正常显示）。
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.serialization.json)
            // AI 回答气泡的 Markdown 渲染（代码块 / 列表 / 加粗）；纯 Compose 实现。
            implementation(libs.markdown.renderer.m3)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}