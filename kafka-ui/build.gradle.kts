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
            implementation(compose.material3)
            // composeResources（品牌图标 drawable）需要资源 API 才能生成访问器。
            implementation(compose.components.resources)
            // stringResource（i18n 查表）来自 ui-common 的 LanguageResources。
            implementation(projects.uiCommon)
        }
    }
}
