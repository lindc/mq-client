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
            // 新建连接表单（ConnectionFormUi 实现）用本模块对应的 service：
            // Provider 列表来自 AiAdapterRegistry。
            implementation(projects.openaiService)
            // 注册表 / 契约（openai-service 已 api 传递，这里显式声明表明直接使用）。
            implementation(projects.aiCore)
            // 连接落盘（AiConnectionRepository）现在归 AI 模块（ai-service）。
            implementation(projects.aiService)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // Material Icons Extended：跨平台一致的矢量图标（Linux 无 Emoji 字体也能正常显示）。
            implementation(compose.materialIconsExtended)
            // composeResources（logo 等品牌素材）需要资源 API 才能生成访问器。
            implementation(compose.components.resources)
            // 表单字段组件（LabeledField / SettingsDropdownRow）与 i18n。
            implementation(projects.uiCommon)
        }
    }
}
