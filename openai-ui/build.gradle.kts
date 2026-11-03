plugins {
    id("mq.kotlin-jvm")
    id("mq.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // ModuleIcon / ConnectionFormUi 契约（本模块两个 SPI 实现）。
            implementation(projects.appCore)
            // Provider 预置（AiProviders）在 openai-service；ai-core 的契约类型随它传出。
            implementation(projects.openaiService)
            implementation(projects.aiCore)
            // 「新建连接」表单本体与 ModelComboBox 都在 ai-ui（两个 provider 共用一份）。
            implementation(projects.aiUi)
            implementation(libs.compose.runtime)
            // Painter（ModuleIcon 的返回类型）在 ui-graphics 里。
            implementation(libs.compose.ui.graphics)
            // composeResources（品牌 logo）需要资源 API 才能生成访问器。
            implementation(compose.components.resources)
        }
    }
}
