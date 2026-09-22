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
            // IbmMQConfig / IbmMQModule（testConnection）都在那里。
            implementation(projects.ibmMqService)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(compose.material3)
            // composeResources（品牌图标 drawable）需要资源 API 才能生成访问器。
            implementation(compose.components.resources)
            // 表单字段组件（LabeledField / PressableButton / SettingsDropdownRow）与 i18n。
            implementation(projects.uiCommon)
        }
    }
}
