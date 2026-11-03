// 所有模块的公共底座：Kotlin Multiplatform + 单一 JVM target + 工具链 25。
// 各模块不再重复这三行（原来 32 个 build 文件各写一遍）。
// 插件按 id 引用（不带版本），版本由本 build 的 build.gradle.kts 作为实现依赖提供。
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm()
    jvmToolchain(25)
}
