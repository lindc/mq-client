// 纯 JVM 模块底座（不使用 Compose 的模块）：kotlin("jvm") + 工具链 25。
// 与 mq.kotlin-jvm 的区别：KMP 插件由 Compose 强拉，故只在不用 Compose 的地方用本插件，
// 彻底去掉 commonMain / jvmMain 这类多平台源集与 expect/actual。
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(25)
}
