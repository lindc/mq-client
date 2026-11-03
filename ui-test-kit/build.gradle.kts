plugins {
    id("mq.jvm")
}

dependencies {
    // ConnectionPersistence 端口（app-core）：记录落盘线程的替身实现它，
    // 消费方（各 *-ui 的 jvmTest）因此能看到这个类型。
    api(projects.appCore)
    // 工具函数签名暴露 ComposeUiTest / Offset —— api 透传，消费方免重复声明。
    // 注意：**不带** compose.desktop.currentOs（Skia 原生运行时由 compose 插件
    // 按宿主机注入；currentOs accessor 在本模块不可用，坐标硬编码又不跨平台）
    // —— 消费方在自己的 jvmTest 里声明 implementation(compose.desktop.currentOs)。
    api(libs.compose.ui.test)
}
