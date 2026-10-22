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
            // Provider 预置（AnthropicProviders）与品牌归属本模块；AiAdapterRegistry
            // 的类型引用在 ai-core。
            implementation(projects.anthropicService)
            // 注册表 / 契约在 ai-core；不再依赖 openai-service。
            implementation(projects.aiCore)
            // 新建连接表单复用 openai-ui 的 ModelComboBox（AI 专属控件，与 openai-service
            // 同理：公共件先归第一个用户，出现第三处再抽 ui-common）。
            implementation(projects.openaiUi)
            // 连接落盘（AiConnectionRepository）归 ai-service。
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
