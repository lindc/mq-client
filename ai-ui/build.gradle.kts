plugins {
    id("mq.kotlin-jvm")
    id("mq.compose")
    id("mq.serialization")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
            // 面板历史 / 审计记录的读写（chat_history 表归 ai-service 管）。
            implementation(projects.aiService)
            // AI 契约 / 注册表（发送链路只用契约，不需要具体 provider）。
            implementation(projects.aiCore)
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