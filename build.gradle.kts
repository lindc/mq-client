// Root aggregation project: no sources of its own. Every module declares its
// own targets and dependencies (see settings.gradle.kts for the list).
// The shared Kotlin/Compose plugins are resolved here once (`apply false`) so
// the subprojects' `alias(libs.plugins.*)` all reuse the same classloader —
// without this, Gradle warns that the Kotlin plugin gets loaded per-subproject.
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    // 以下两个被 convention 插件（mq.serialization / mq.sqldelight）按需 apply，
    // 这里登记版本，使消费工程能按 id 解析（与 gradle/plugins 的 build.gradle.kts 呼应）。
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.sqldelight) apply false
}