plugins {
    `kotlin-dsl`
}

dependencies {
    // 预编译脚本（mq.*.gradle.kts）里按 id 引用这些插件（不能带版本），必须由本 build
    // 作为实现依赖提供。Gradle 插件经「插件标记构件」（<pluginId>:<pluginId>.gradle.plugin）
    // 暴露其 META-INF 描述符，所以这里引标记构件而非裸实现 jar。版本来自共享版本目录。
    implementation("org.jetbrains.kotlin.multiplatform:org.jetbrains.kotlin.multiplatform.gradle.plugin:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin.plugin.serialization:org.jetbrains.kotlin.plugin.serialization.gradle.plugin:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.compose:org.jetbrains.compose.gradle.plugin:${libs.versions.composeMultiplatform.get()}")
    implementation("app.cash.sqldelight:app.cash.sqldelight.gradle.plugin:${libs.versions.sqldelight.get()}")
}
