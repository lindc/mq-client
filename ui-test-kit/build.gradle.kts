plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        jvmMain.dependencies {
            // 工具函数签名暴露 ComposeUiTest / Offset —— api 透传，消费方免重复声明。
            // 注意：**不带** compose.desktop.currentOs（Skia 原生运行时由 compose 插件
            // 按宿主机注入；currentOs accessor 在本模块不可用，坐标硬编码又不跨平台）
            // —— 消费方在自己的 jvmTest 里声明 implementation(compose.desktop.currentOs)。
            api(libs.compose.ui.test)
        }
    }
}
