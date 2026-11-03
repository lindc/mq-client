plugins {
    id("mq.kotlin-jvm")
    id("mq.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // ModuleIcon / ConnectionFormUi 契约（本模块两个 SPI 实现）。
            implementation(projects.appCore)
            // Provider 预置（AnthropicProviders）与品牌归属本模块。
            implementation(projects.anthropicService)
            implementation(projects.aiCore)
            // 「新建连接」表单本体在 ai-ui。**不**依赖 openai-ui：两个平级 provider
            // 之间不该有横向依赖（ModelComboBox 曾迫使我们这么做过）。
            implementation(projects.aiUi)
            implementation(libs.compose.runtime)
            // Painter（ModuleIcon 的返回类型）在 ui-graphics 里。
            implementation(libs.compose.ui.graphics)
            // composeResources（品牌 logo）需要资源 API 才能生成访问器。
            implementation(compose.components.resources)
        }
    }
}
